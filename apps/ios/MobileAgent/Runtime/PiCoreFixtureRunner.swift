import CryptoKit
import SwiftUI
import WebKit

struct PiAgentResult {
    let finalText: String
    let messagesJSON: String
}

@MainActor
final class PiRunMetrics {
    private let startedUptime = ProcessInfo.processInfo.systemUptime
    private var externalForegroundStartedUptime: TimeInterval?
    var modelCalls = 0
    var toolCalls = 0
    var agentTurns = 0
    var approvalInteractions = 0
    var foregroundInterruptMilliseconds = 0
    var observationFailures = 0
    var actionFailures = 0
    var lastTargetReference: String?
    var failureStage: String?
    var evidence: [[String: Any]] = []

    var durationMilliseconds: Int {
        Int((ProcessInfo.processInfo.systemUptime - startedUptime) * 1000)
    }

    func recordTool(name: String, execution: ToolExecution) {
        toolCalls += 1
        let data = execution.json.data(using: .utf8)
        let parsed = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let failed = parsed?["error"] != nil
        let errorCode = (parsed?["error"] as? String)?.split(separator: ":").first.map(String.init)
        if failed && name == "device_observe" { observationFailures += 1 }
        if failed && name != "device_observe" { actionFailures += 1 }
        if let invoke = parsed?["last_invoke"] as? [String: Any] {
            lastTargetReference = invoke["url"] as? String ?? invoke["capability"] as? String
        }
        if UIApplication.shared.applicationState != .active, externalForegroundStartedUptime == nil {
            externalForegroundStartedUptime = ProcessInfo.processInfo.systemUptime
        } else if UIApplication.shared.applicationState == .active {
            finishForegroundInterval()
        }
        evidence.append([
            "observed_at": ISO8601DateFormatter().string(from: Date()),
            "tool": name,
            "result_sha256": sha256(execution.json),
            "screenshot_sha256": NSNull(),
            "target_reference": lastTargetReference ?? NSNull(),
            "error_code": errorCode ?? NSNull(),
            "is_error": failed,
        ])
    }

    func finishForegroundInterval() {
        if let started = externalForegroundStartedUptime {
            foregroundInterruptMilliseconds += Int((ProcessInfo.processInfo.systemUptime - started) * 1000)
        }
        externalForegroundStartedUptime = nil
    }

    private func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

@MainActor
final class PiCoreFixtureRunner: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandlerWithReply {
    enum Status {
        case idle
        case running
        case passed
        case failed
    }

    static let shared = PiCoreFixtureRunner()

    @Published private(set) var status: Status = .idle
    @Published private(set) var detail = "未运行"

    private static let bridgeName = "MobileAgentNative"
    private static let pageURL = URL(string: "https://mobile-agent.local/runtime")!
    private weak var webView: WKWebView?
    private var activeRun: ActiveRun?
    private let fixtureRuntime = IOSDeviceRuntime()
    private var bridgeRequestTasks: [UUID: Task<Void, Never>] = [:]
    #if DEBUG
    private var debugModelDelayConsumed = false
    private var debugBackgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var debugBenchmarkStore: BenchmarkRunStore?
    private var debugBenchmarkMetrics: PiRunMetrics?
    #endif

    private final class NativeResponse: @unchecked Sendable {
        let value: [String: Any]
        init(_ value: [String: Any]) { self.value = value }
    }

    private final class ActiveRun {
        let client: OpenAICompatibleClient
        let runtime: IOSDeviceRuntime
        let onStep: (String) -> Void
        let requestApproval: (String) async -> Bool
        let continuation: CheckedContinuation<PiAgentResult, Error>
        let metrics: PiRunMetrics
        var modelTask: Task<NativeResponse, Never>?

        init(
            client: OpenAICompatibleClient,
            runtime: IOSDeviceRuntime,
            onStep: @escaping (String) -> Void,
            requestApproval: @escaping (String) async -> Bool,
            continuation: CheckedContinuation<PiAgentResult, Error>,
            metrics: PiRunMetrics
        ) {
            self.client = client
            self.runtime = runtime
            self.onStep = onStep
            self.requestApproval = requestApproval
            self.continuation = continuation
            self.metrics = metrics
        }
    }

    func makeWebView() -> WKWebView {
        let controller = WKUserContentController()
        controller.addScriptMessageHandler(self, contentWorld: .page, name: Self.bridgeName)
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        self.webView = webView
        return webView
    }

    func start(in webView: WKWebView) {
        guard status == .idle || status == .failed else { return }
        status = .running
        detail = "Pi core 正在调用原生工具"
        #if DEBUG
        beginDebugBackgroundTimeIfNeeded()
        beginDebugBenchmarkIfNeeded()
        #endif
        webView.loadHTMLString(
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
            baseURL: Self.pageURL
        )
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard status == .running else { return }
        guard let bundleURL = Bundle.main.url(forResource: "pi-mobile-runtime", withExtension: "js") else {
            fail("PI_CORE_BUNDLE_MISSING")
            return
        }
        do {
            let bundle = try String(contentsOf: bundleURL, encoding: .utf8)
            #if DEBUG
            let command = "\(bundle)\n;void window.PiMobileRuntime.run({prompt:'观察设备',platform:'ios'});"
            #else
            let command = bundle
            status = .passed
            detail = "Pi runtime 已就绪"
            NSLog("MobileAgentPi runtime ready")
            #endif
            webView.evaluateJavaScript(command) { [weak self] _, error in
                if let error { self?.fail("PI_CORE_JAVASCRIPT_ERROR:\(error.localizedDescription)") }
            }
        } catch {
            fail("PI_CORE_BUNDLE_READ_FAILED:\(error.localizedDescription)")
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let reason = "WKWEBVIEW_CONTENT_PROCESS_TERMINATED"
        bridgeRequestTasks.values.forEach { $0.cancel() }
        bridgeRequestTasks.removeAll()
        if let run = activeRun {
            activeRun = nil
            run.metrics.failureStage = "runtime:webview_renderer"
            run.modelTask?.cancel()
            run.continuation.resume(throwing: AgentRuntimeError.operationFailed(reason))
        }
        fail(reason)
        Task { @MainActor [weak self, weak webView] in
            try? await Task.sleep(for: .milliseconds(250))
            guard let self, let webView else { return }
            self.start(in: webView)
        }
    }

    func run(
        client: OpenAICompatibleClient,
        runtime: IOSDeviceRuntime,
        prompt: String,
        messagesJSON: String?,
        metrics: PiRunMetrics,
        onStep: @escaping (String) -> Void,
        requestApproval: @escaping (String) async -> Bool
    ) async throws -> PiAgentResult {
        for _ in 0..<100 where status != .passed {
            if status == .failed { throw AgentRuntimeError.operationFailed("PI_RUNTIME_UNAVAILABLE:\(detail)") }
            try await Task.sleep(for: .milliseconds(50))
        }
        guard status == .passed, let webView else {
            throw AgentRuntimeError.operationFailed("PI_RUNTIME_NOT_READY")
        }
        guard activeRun == nil else { throw AgentRuntimeError.operationFailed("PI_AGENT_ALREADY_RUNNING") }
        var input: [String: Any] = ["prompt": prompt, "platform": "ios"]
        if let messagesJSON,
           let data = messagesJSON.data(using: .utf8),
           let messages = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            input["messages"] = messages
        }
        let encoded = try input.jsonString()
        return try await withCheckedThrowingContinuation { continuation in
            activeRun = ActiveRun(
                client: client,
                runtime: runtime,
                onStep: onStep,
                requestApproval: requestApproval,
                continuation: continuation,
                metrics: metrics
            )
            webView.evaluateJavaScript("void window.PiMobileRuntime.run(\(encoded));") { [weak self] _, error in
                guard let error, let self, let run = self.activeRun else { return }
                self.activeRun = nil
                run.continuation.resume(throwing: AgentRuntimeError.operationFailed("PI_JAVASCRIPT_ERROR:\(error.localizedDescription)"))
            }
        }
    }

    func cancel() {
        activeRun?.modelTask?.cancel()
        webView?.evaluateJavaScript("window.PiMobileRuntime.cancel();")
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
    ) {
        let allowed = navigationAction.request.url?.host == Self.pageURL.host
        decisionHandler(allowed ? .allow : .cancel)
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage,
        replyHandler: @escaping @MainActor @Sendable (Any?, String?) -> Void
    ) {
        guard message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.host == Self.pageURL.host,
              let encoded = message.body as? String,
              let data = encoded.data(using: .utf8),
              let request = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = request["id"] as? String,
              let method = request["method"] as? String else {
            replyHandler(nil, "NATIVE_REQUEST_INVALID")
            fail("NATIVE_REQUEST_INVALID")
            return
        }

        let taskToken = UUID()
        let task = Task { @MainActor in
            defer { bridgeRequestTasks[taskToken] = nil }
            let response = await handleRequest(id: id, method: method, request: request)
            guard !Task.isCancelled else { return }
            guard let responseData = try? JSONSerialization.data(withJSONObject: response),
                  let responseString = String(data: responseData, encoding: .utf8) else {
                replyHandler(nil, "NATIVE_RESPONSE_INVALID")
                fail("NATIVE_RESPONSE_INVALID")
                return
            }
            replyHandler(responseString, nil)
        }
        bridgeRequestTasks[taskToken] = task
    }

    private func handleRequest(id: String, method: String, request: [String: Any]) async -> [String: Any] {
        let params = request["params"] as? [String: Any] ?? [:]
        #if DEBUG
        NSLog("MobileAgentPi native request method=\(method) state=\(UIApplication.shared.applicationState.rawValue)")
        #endif
        switch method {
        case "device_observe":
            return handleFixtureObservation(id: id)
        case "model_complete":
            guard let activeRun else { return await handleModelCompletion(id: id, params: params) }
            activeRun.metrics.failureStage = "model"
            activeRun.metrics.modelCalls += 1
            let task = Task { @MainActor in
                do {
                    let response = try await activeRun.client.complete(
                        messages: params["messages"] as? [[String: Any]] ?? [],
                        tools: params["tools"] as? [[String: Any]] ?? []
                    )
                    activeRun.metrics.failureStage = nil
                    return NativeResponse(success(id: id, result: response))
                } catch {
                    return NativeResponse(failure(id: id, error: error.localizedDescription))
                }
            }
            activeRun.modelTask = task
            let response = await task.value.value
            if self.activeRun === activeRun { activeRun.modelTask = nil }
            return response
        case "tool_execute":
            guard let activeRun else { return await handleFixtureToolExecution(id: id, params: params) }
            return await handleToolExecution(id: id, params: params, run: activeRun)
        case "runtime_event":
            return success(id: id, result: ["accepted": true])
        case "agent_complete":
            guard let activeRun else { return handleCompletion(id: id, params: params) }
            return handleAgentCompletion(id: id, params: params, run: activeRun)
        default:
            return failure(id: id, error: "NATIVE_METHOD_UNSUPPORTED")
        }
    }

    private func handleToolExecution(id: String, params: [String: Any], run: ActiveRun) async -> [String: Any] {
        guard let name = params["name"] as? String else { return failure(id: id, error: "TOOL_NAME_MISSING") }
        let arguments = params["arguments"] as? [String: Any] ?? [:]
        let label = stepLabel(name: name, arguments: arguments)
        run.metrics.failureStage = "tool:\(name)"
        run.onStep(label)
        let execution: ToolExecution
        let needsApproval = requiresApproval(name: name, arguments: arguments)
        if needsApproval { run.metrics.approvalInteractions += 1 }
        if needsApproval, !(await run.requestApproval(label)) {
            execution = ToolExecution(json: "{\"error\":\"USER_DENIED\"}")
        } else {
            do {
                execution = try await run.runtime.execute(name: name, arguments: arguments)
            } catch {
                execution = ToolExecution(json: "{\"error\":\"\(escapeJSON(error.localizedDescription))\",\"platform\":\"ios\"}")
            }
        }
        run.metrics.recordTool(name: name, execution: execution)
        run.metrics.failureStage = nil
        return success(id: id, result: ["json": execution.json, "isError": execution.json.contains("\"error\"")])
    }

    private func handleFixtureObservation(id: String) -> [String: Any] {
        do {
            let execution = try fixtureRuntime.observe()
            guard let data = execution.json.data(using: .utf8),
                  let observation = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return failure(id: id, error: "FIXTURE_OBSERVATION_INVALID")
            }
            return success(id: id, result: observation)
        } catch {
            return failure(id: id, error: error.localizedDescription)
        }
    }

    private func handleFixtureToolExecution(id: String, params: [String: Any]) async -> [String: Any] {
        guard let name = params["name"] as? String else { return failure(id: id, error: "FIXTURE_TOOL_NAME_MISSING") }
        let arguments = params["arguments"] as? [String: Any] ?? [:]
        #if DEBUG
        if ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_FIXTURE_TOOL"] == "open_settings",
           name == "device_invoke",
           arguments["capability"] as? String == "open_settings" {
            return await handleFixtureOpenSettings(id: id, arguments: arguments)
        }
        #endif
        guard name == "device_observe" else { return failure(id: id, error: "FIXTURE_TOOL_UNSUPPORTED") }
        do {
            let execution = try await fixtureRuntime.execute(
                name: "device_observe",
                arguments: arguments
            )
            guard let data = execution.json.data(using: .utf8),
                  let observation = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  observation["platform"] as? String == "ios",
                  observation["application_state"] is String,
                  let capabilities = observation["capabilities"] as? [String: Any],
                  capabilities["global_ui_observe"] as? Bool == false,
                  capabilities["global_ui_control"] as? Bool == false else {
                return failure(id: id, error: "FIXTURE_IOS_CAPABILITY_MATRIX_INVALID")
            }
            #if DEBUG
            NSLog("MobileAgentPi fixture used IOSDeviceRuntime.observe; state=\(observation["application_state"] as? String ?? "unknown")")
            #endif
            return success(id: id, result: ["json": execution.json, "isError": false])
        } catch {
            return failure(id: id, error: error.localizedDescription)
        }
    }

    #if DEBUG
    private func handleFixtureOpenSettings(id: String, arguments: [String: Any]) async -> [String: Any] {
        do {
            debugBenchmarkMetrics?.failureStage = "tool:device_invoke"
            let invocation = try await fixtureRuntime.execute(name: "device_invoke", arguments: arguments)
            guard let data = invocation.json.data(using: .utf8),
                  let initialObservation = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let lastInvoke = initialObservation["last_invoke"] as? [String: Any],
                  lastInvoke["capability"] as? String == "open_settings",
                  lastInvoke["result"] as? String == "request_accepted_by_system",
                  lastInvoke["externally_verified"] as? Bool == false else {
                return failure(id: id, error: "FIXTURE_IOS_OPEN_SETTINGS_RESULT_INVALID")
            }
            debugBenchmarkMetrics?.recordTool(name: "device_invoke", execution: invocation)
            debugBenchmarkMetrics?.failureStage = nil
            var postInvokeExecution = invocation
            var applicationState = initialObservation["application_state"] as? String ?? "unknown"
            for _ in 0..<20 where applicationState != "background" {
                try await Task.sleep(for: .milliseconds(100))
                postInvokeExecution = try fixtureRuntime.observe()
                guard let observedData = postInvokeExecution.json.data(using: .utf8),
                      let observation = try JSONSerialization.jsonObject(with: observedData) as? [String: Any] else {
                    return failure(id: id, error: "FIXTURE_IOS_POST_INVOKE_OBSERVATION_INVALID")
                }
                applicationState = observation["application_state"] as? String ?? "unknown"
            }
            guard applicationState == "background" else {
                return failure(id: id, error: "FIXTURE_IOS_OPEN_SETTINGS_DID_NOT_BACKGROUND")
            }
            NSLog("MobileAgentPi fixture used IOSDeviceRuntime.invoke; capability=open_settings state=\(applicationState)")
            return success(id: id, result: ["json": postInvokeExecution.json, "isError": false])
        } catch {
            return failure(id: id, error: error.localizedDescription)
        }
    }
    #endif

    private func handleAgentCompletion(id: String, params: [String: Any], run: ActiveRun) -> [String: Any] {
        activeRun = nil
        if let error = params["error"] as? String, !error.isEmpty {
            run.continuation.resume(throwing: AgentRuntimeError.operationFailed(error))
            return failure(id: id, error: error)
        }
        guard let result = params["result"] as? [String: Any],
              let messages = result["messages"] as? [[String: Any]],
              let data = try? JSONSerialization.data(withJSONObject: messages),
              let messagesJSON = String(data: data, encoding: .utf8) else {
            run.continuation.resume(throwing: AgentRuntimeError.operationFailed("PI_AGENT_RESULT_INVALID"))
            return failure(id: id, error: "PI_AGENT_RESULT_INVALID")
        }
        let events = result["eventTypes"] as? [String] ?? []
        run.metrics.agentTurns = events.filter { $0 == "turn_end" }.count
        let failedToolResults = messages.filter {
            $0["role"] as? String == "toolResult" && $0["isError"] as? Bool == true
        }
        run.metrics.observationFailures = max(
            run.metrics.observationFailures,
            failedToolResults.filter { $0["toolName"] as? String == "device_observe" }.count
        )
        run.metrics.actionFailures = max(
            run.metrics.actionFailures,
            failedToolResults.filter { $0["toolName"] as? String != "device_observe" }.count
        )
        run.metrics.finishForegroundInterval()
        run.continuation.resume(returning: PiAgentResult(
            finalText: result["finalText"] as? String ?? "任务已完成。",
            messagesJSON: messagesJSON
        ))
        return success(id: id, result: ["accepted": true])
    }

    private func handleModelCompletion(id: String, params: [String: Any]) async -> [String: Any] {
        #if DEBUG
        debugBenchmarkMetrics?.modelCalls += 1
        debugBenchmarkMetrics?.failureStage = "model"
        #endif
        do {
            try await consumeDebugModelDelayIfNeeded()
        } catch {
            return failure(id: id, error: "MODEL_DELAY_CANCELLED")
        }
        guard let messages = params["messages"] as? [[String: Any]] else {
            return failure(id: id, error: "MODEL_MESSAGES_MISSING")
        }
        let hasToolResult = messages.contains { $0["role"] as? String == "tool" }
        let message: [String: Any] = hasToolResult
            ? ["content": "Pi mobile runtime complete."]
            : fixtureToolCallMessage()
        #if DEBUG
        debugBenchmarkMetrics?.failureStage = nil
        #endif
        return success(id: id, result: [
            "choices": [["message": message, "finish_reason": hasToolResult ? "stop" : "tool_calls"]],
        ])
    }

    private func fixtureToolCallMessage() -> [String: Any] {
        #if DEBUG
        if ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_FIXTURE_TOOL"] == "open_settings" {
            return [
                "content": NSNull(),
                "tool_calls": [[
                    "id": "open-settings-1",
                    "type": "function",
                    "function": ["name": "device_invoke", "arguments": "{\"capability\":\"open_settings\",\"params\":{}}"],
                ]],
            ]
        }
        #endif
        return [
            "content": NSNull(),
            "tool_calls": [[
                "id": "observe-1",
                "type": "function",
                "function": ["name": "device_observe", "arguments": "{\"include_screen\":false}"],
            ]],
        ]
    }

    private func consumeDebugModelDelayIfNeeded() async throws {
        #if DEBUG
        guard !debugModelDelayConsumed,
              let raw = ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_MODEL_DELAY_MS"],
              let requested = UInt64(raw), requested > 0 else { return }
        debugModelDelayConsumed = true
        let delayMilliseconds = min(requested, 5 * 60 * 1000)
        NSLog("MobileAgentPi debug model delay started; delayMs=\(delayMilliseconds) state=\(UIApplication.shared.applicationState.rawValue)")
        do {
            try await Task.sleep(for: .milliseconds(delayMilliseconds))
            NSLog("MobileAgentPi debug model delay finished; delayMs=\(delayMilliseconds) state=\(UIApplication.shared.applicationState.rawValue)")
        } catch {
            NSLog("MobileAgentPi debug model delay cancelled; delayMs=\(delayMilliseconds) state=\(UIApplication.shared.applicationState.rawValue)")
            throw error
        }
        #endif
    }

    private func handleCompletion(id: String, params: [String: Any]) -> [String: Any] {
        if let error = params["error"] as? String, !error.isEmpty {
            fail(error)
            return failure(id: id, error: error)
        }
        guard let result = params["result"] as? [String: Any],
              result["finalText"] as? String == "Pi mobile runtime complete.",
              let messages = result["messages"] as? [[String: Any]],
              messages.compactMap({ $0["role"] as? String }) == ["user", "assistant", "toolResult", "assistant"],
              let events = result["eventTypes"] as? [String],
              events.last == "agent_end" else {
            fail("PI_CORE_FIXTURE_RESULT_INVALID")
            return failure(id: id, error: "PI_CORE_FIXTURE_RESULT_INVALID")
        }
        status = .passed
        detail = "Pi runtime + iOS model/tool bridge 已通过"
        #if DEBUG
        if let metrics = debugBenchmarkMetrics, let store = debugBenchmarkStore {
            let events = result["eventTypes"] as? [String] ?? []
            metrics.agentTurns = events.filter { $0 == "turn_end" }.count
            store.complete(
                answer: "{\"request_accepted\":true}",
                notes: "Debug deterministic fixture; system accepted open_settings; not online model evidence."
            )
            debugBenchmarkStore = nil
            debugBenchmarkMetrics = nil
        }
        finishDebugBackgroundTime()
        NSLog("MobileAgentPi fixture passed; state=\(UIApplication.shared.applicationState.rawValue)")
        #endif
        return success(id: id, result: ["accepted": true])
    }

    private func stepLabel(name: String, arguments: [String: Any]) -> String {
        switch name {
        case "device_observe": "正在读取 iOS 能力状态"
        case "device_act": "正在检查界面操作能力"
        case "device_invoke": "正在调用 \(arguments["capability"] as? String ?? "系统能力")"
        default: "正在执行 \(name)"
        }
    }

    private func requiresApproval(name: String, arguments: [String: Any]) -> Bool {
        guard name == "device_invoke", let capability = arguments["capability"] as? String else { return false }
        return ["dial", "share", "run_shortcut"].contains(capability)
    }

    private func escapeJSON(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    }

    private func success(id: String, result: Any) -> [String: Any] {
        ["id": id, "result": result]
    }

    private func failure(id: String, error: String) -> [String: Any] {
        ["id": id, "error": error]
    }

    private func fail(_ message: String) {
        status = .failed
        detail = message
        #if DEBUG
        if let store = debugBenchmarkStore {
            store.fail(AgentRuntimeError.operationFailed(message))
            debugBenchmarkStore = nil
            debugBenchmarkMetrics = nil
        }
        finishDebugBackgroundTime()
        NSLog("MobileAgentPi fixture failed; detail=\(message) state=\(UIApplication.shared.applicationState.rawValue)")
        #else
        NSLog("MobileAgentPi runtime failed; detail=\(message)")
        #endif
    }

    #if DEBUG
    private func beginDebugBenchmarkIfNeeded() {
        guard ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_RECORD_BENCHMARK"] == "1",
              ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_FIXTURE_TOOL"] == "open_settings",
              debugBenchmarkStore == nil else { return }
        let requestedTaskID = ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_BENCHMARK_TASK_ID"] ?? "L1"
        let taskID = ["L1", "R1"].contains(requestedTaskID) ? requestedTaskID : "L1"
        let metrics = PiRunMetrics()
        debugBenchmarkMetrics = metrics
        debugBenchmarkStore = BenchmarkRunStore(
            prompt: "[BENCH:\(taskID)] Open this app's Settings page and report whether iOS accepted the request.",
            metrics: metrics,
            model: "deterministic-fixture"
        )
    }

    private func beginDebugBackgroundTimeIfNeeded() {
        guard ProcessInfo.processInfo.environment["MOBILE_AGENT_DEBUG_USE_BACKGROUND_TASK"] == "1",
              debugBackgroundTask == .invalid else { return }
        debugBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "MobileAgentPiFixture") { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                NSLog("MobileAgentPi debug background task expired; state=\(UIApplication.shared.applicationState.rawValue)")
                self.finishDebugBackgroundTime()
                self.fail("DEBUG_BACKGROUND_TASK_EXPIRED")
                Task { @MainActor [weak webView = self.webView] in
                    _ = try? await webView?.evaluateJavaScript("window.PiMobileRuntime.cancel();")
                }
            }
        }
        NSLog("MobileAgentPi debug background task started; remaining=\(UIApplication.shared.backgroundTimeRemaining)")
    }

    private func finishDebugBackgroundTime() {
        guard debugBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(debugBackgroundTask)
        debugBackgroundTask = .invalid
        NSLog("MobileAgentPi debug background task ended; state=\(UIApplication.shared.applicationState.rawValue)")
    }
    #endif
}

struct PiCoreFixtureHost: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let webView = PiCoreFixtureRunner.shared.makeWebView()
        PiCoreFixtureRunner.shared.start(in: webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
