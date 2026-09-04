import Foundation
import UIKit
import UserNotifications

@MainActor
final class AgentSession: ObservableObject {
    static let shared = AgentSession()

    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var status: AgentStatus = .idle
    @Published private(set) var isRunning = false
    @Published private(set) var currentStep = ""
    @Published private(set) var approval: ApprovalRequest?
    @Published private(set) var keyConfigured: Bool
    @Published private(set) var endpoint: ModelEndpoint
    @Published var configurationError: String?

    private let keychain = KeychainStore()
    private let endpointStore = ModelEndpointStore()
    private var task: Task<Void, Never>?
    private var approvalContinuation: CheckedContinuation<Bool, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private init() {
        BenchmarkRunStore.reconcileInterrupted()
        endpoint = endpointStore.load()
        keyConfigured = keychain.load()?.isEmpty == false
    }

    func saveConfiguration(apiKey: String, baseURL: String, model: String) {
        do {
            let validatedEndpoint = try ModelEndpoint(baseURL: baseURL, model: model)
            try keychain.save(apiKey.trimmingCharacters(in: .whitespacesAndNewlines))
            endpoint = try endpointStore.save(
                baseURL: validatedEndpoint.baseURL.absoluteString,
                model: validatedEndpoint.model
            )
            keyConfigured = true
            configurationError = nil
        } catch {
            configurationError = error.localizedDescription
        }
    }

    func deleteAPIKey() {
        stop()
        keychain.delete()
        keyConfigured = false
    }

    func start(_ prompt: String) {
        let normalized = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isRunning, !normalized.isEmpty else { return }
        guard let key = keychain.load(), !key.isEmpty else {
            add(.error, "请先保存模型 API Key。")
            return
        }

        isRunning = true
        let metrics = PiRunMetrics()
        let benchmarkRun = BenchmarkRunStore(
            prompt: normalized,
            metrics: metrics,
            model: endpoint.model,
            modelEndpointHost: endpoint.host
        )
        let benchmarkMode = normalized.hasPrefix("[BENCH:")
        let runtimePrompt = benchmarkMode
            ? "\(normalized)\n\n<benchmark_contract>最终只输出合法 JSON，不要 Markdown，不要代码围栏；字段和数量严格遵守任务中给出的结构。</benchmark_contract>"
            : normalized
        status = .thinking
        currentStep = "正在读取 iOS Context"
        add(.user, normalized)
        beginBackgroundTime()

        task = Task { [weak self] in
            guard let self else { return }
            defer { self.finishBackgroundTime() }
            do {
                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                let result = try await PiCoreFixtureRunner.shared.run(
                    client: OpenAICompatibleClient(apiKey: key, endpoint: endpoint),
                    runtime: IOSDeviceRuntime(),
                    prompt: runtimePrompt,
                    messagesJSON: benchmarkMode ? nil : UserDefaults.standard.string(forKey: "pi-agent-messages"),
                    metrics: metrics,
                    onStep: { [weak self] step in
                        self?.status = .acting
                        self?.currentStep = step
                        self?.add(.status, step)
                    },
                    requestApproval: { [weak self] description in
                        guard let self else { return false }
                        return await self.waitForApproval(description)
                    }
                )
                let answer = result.finalText
                if !benchmarkMode { UserDefaults.standard.set(result.messagesJSON, forKey: "pi-agent-messages") }
                benchmarkRun.complete(answer: answer)
                add(.agent, answer)
                isRunning = false
                status = .complete
                currentStep = "任务完成"
                await notifyIfBackground(title: "Mobile Agent", body: answer)
            } catch is CancellationError {
                benchmarkRun.cancel()
                add(.status, "任务已停止")
                isRunning = false
                status = .idle
                currentStep = ""
            } catch {
                benchmarkRun.fail(error)
                add(.error, error.localizedDescription)
                isRunning = false
                status = .error
                currentStep = "任务失败"
                await notifyIfBackground(title: "Mobile Agent 任务失败", body: error.localizedDescription)
            }
        }
    }

    func stop() {
        approvalContinuation?.resume(returning: false)
        approvalContinuation = nil
        approval = nil
        PiCoreFixtureRunner.shared.cancel()
        task?.cancel()
    }

    func resolveApproval(_ allowed: Bool) {
        approvalContinuation?.resume(returning: allowed)
        approvalContinuation = nil
        approval = nil
        status = .acting
    }

    private func waitForApproval(_ description: String) async -> Bool {
        status = .waitingApproval
        currentStep = "等待用户确认"
        approval = ApprovalRequest(description: description)
        return await withCheckedContinuation { continuation in
            approvalContinuation = continuation
        }
    }

    private func add(_ role: MessageRole, _ text: String) {
        messages.append(ChatMessage(role: role, text: text))
    }

    private func beginBackgroundTime() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "MobileAgentTask") { [weak self] in
            Task { @MainActor in self?.stop() }
        }
    }

    private func finishBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func notifyIfBackground(title: String, body: String) async {
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = String(body.prefix(180))
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
