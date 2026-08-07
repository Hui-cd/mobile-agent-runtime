import Foundation

@MainActor
final class AgentEngine {
    private let client: KimiClient
    private let runtime: IOSDeviceRuntime
    private let onStep: (String) -> Void
    private let requestApproval: (String) async -> Bool

    init(
        client: KimiClient,
        runtime: IOSDeviceRuntime,
        onStep: @escaping (String) -> Void,
        requestApproval: @escaping (String) async -> Bool
    ) {
        self.client = client
        self.runtime = runtime
        self.onStep = onStep
        self.requestApproval = requestApproval
    }

    func run(prompt: String, history: [ChatMessage]) async throws -> String {
        let initialContext = try runtime.observe().json
        var messages: [[String: Any]] = [["role": "system", "content": Self.systemPrompt]]
        for message in history.suffix(12) {
            switch message.role {
            case .user: messages.append(["role": "user", "content": message.text])
            case .agent: messages.append(["role": "assistant", "content": message.text])
            default: break
            }
        }
        messages.append([
            "role": "user",
            "content": """
            用户当前请求：
            \(prompt)

            <current_device_context>
            \(initialContext)
            </current_device_context>
            """,
        ])

        let tools = toolDefinitions()
        for _ in 0..<16 {
            try Task.checkCancellation()
            let response = try await client.complete(messages: messages, tools: tools)
            guard let choices = response["choices"] as? [[String: Any]],
                  let choice = choices.first,
                  let assistant = choice["message"] as? [String: Any] else {
                throw AgentRuntimeError.operationFailed("KIMI_MALFORMED_RESPONSE")
            }
            messages.append(assistant)
            let calls = assistant["tool_calls"] as? [[String: Any]] ?? []
            if calls.isEmpty {
                let content = assistant["content"] as? String
                return content?.isEmpty == false ? content! : "任务已完成。"
            }

            for call in calls {
                guard let id = call["id"] as? String,
                      let function = call["function"] as? [String: Any],
                      let name = function["name"] as? String else { continue }
                let arguments = try parseArguments(function["arguments"] as? String)
                let label = stepLabel(name: name, arguments: arguments)
                onStep(label)

                let execution: ToolExecution
                if requiresApproval(name: name, arguments: arguments), !(await requestApproval(label)) {
                    execution = ToolExecution(json: try ["error": "USER_DENIED"].jsonString())
                } else {
                    do {
                        execution = try await runtime.execute(name: name, arguments: arguments)
                    } catch {
                        execution = ToolExecution(json: try [
                            "error": error.localizedDescription,
                            "platform": "ios",
                        ].jsonString())
                    }
                }
                messages.append([
                    "role": "tool",
                    "tool_call_id": id,
                    "content": execution.json,
                ])
            }
        }
        throw AgentRuntimeError.operationFailed("AGENT_STEP_LIMIT")
    }

    private func parseArguments(_ value: String?) throws -> [String: Any] {
        guard let data = (value ?? "{}").data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AgentRuntimeError.invalidArguments("INVALID_TOOL_ARGUMENTS")
        }
        return json
    }

    private func stepLabel(name: String, arguments: [String: Any]) -> String {
        switch name {
        case "device_observe": return "正在读取 iOS 能力状态"
        case "device_act": return "正在检查界面操作能力"
        case "device_invoke": return "正在调用 \(arguments["capability"] as? String ?? "系统能力")"
        default: return "正在执行 \(name)"
        }
    }

    private func requiresApproval(name: String, arguments: [String: Any]) -> Bool {
        guard name == "device_invoke", let capability = arguments["capability"] as? String else { return false }
        return ["dial", "share", "run_shortcut"].contains(capability)
    }

    private func toolDefinitions() -> [[String: Any]] {
        [
            tool(
                name: "device_observe",
                description: "读取 Mobile Agent 自身状态、设备信息和 iOS 能力矩阵。iOS 不允许读取其他 App 的 UI Tree 或截图。",
                parameters: ["type": "object", "properties": [:]]
            ),
            tool(
                name: "device_act",
                description: "统一 GUI 动作原语。iOS 公共 API 不支持跨 App GUI 控制，调用时 Runtime 会返回明确的能力错误。",
                parameters: [
                    "type": "object",
                    "required": ["action"],
                    "properties": [
                        "action": ["type": "string", "enum": ["click", "long_press", "input", "scroll", "swipe", "back", "home"]],
                        "target": ["type": "object", "additionalProperties": true],
                        "value": ["type": "string"],
                    ],
                ]
            ),
            tool(
                name: "device_invoke",
                description: "通过 iOS 公开能力执行跨 App 操作。优先 Universal Link、URL Scheme、App Intent 或用户 Shortcut。",
                parameters: [
                    "type": "object",
                    "required": ["capability", "params"],
                    "properties": [
                        "capability": [
                            "type": "string",
                            "enum": ["open_app", "open_url", "deep_link", "navigate", "dial", "open_settings", "share", "run_shortcut"],
                        ],
                        "params": ["type": "object", "additionalProperties": true],
                    ],
                ]
            ),
        ]
    }

    private func tool(name: String, description: String, parameters: [String: Any]) -> [String: Any] {
        [
            "type": "function",
            "function": ["name": name, "description": description, "parameters": parameters],
        ]
    }

    private static let systemPrompt = """
    你是运行在 iPhone/iPad 内的 Mobile Agent。每次请求会附带最近对话与当前 iOS 能力上下文，你应直接回复或调用工具，不要先输出任务计划。工具面固定为 observe、act、invoke，但必须遵守平台边界：iOS 不提供第三方 App 跨应用读取 UI、截图、模拟点击或输入的公共 API；不要假装可以完成这些动作。能通过 Universal Link、URL Scheme、App Intent、Shortcut、系统地图、拨号或分享面板完成时使用 invoke。涉及拨号、分享或执行 Shortcut 时 Runtime 会要求用户确认。系统接受 URL 只证明请求已交给系统，不能声称已经验证目标 App 内部结果。用中文简洁说明完成结果或需要用户补充的 Shortcut/URL Scheme。
    """
}
