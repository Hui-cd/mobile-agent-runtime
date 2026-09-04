import Foundation

struct ModelEndpoint: Equatable {
    static let defaultBaseURL = "https://api.moonshot.cn/v1"
    static let defaultModel = "kimi-k3"
    static let `default` = try! ModelEndpoint(baseURL: defaultBaseURL, model: defaultModel)

    let baseURL: URL
    let model: String
    let host: String
    let sendsReasoningEffort: Bool

    init(baseURL value: String, model valueModel: String) throws {
        let normalizedBase = value.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"/+$"#, with: "", options: .regularExpression)
        let normalizedModel = valueModel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: normalizedBase),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(), !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              let url = components.url else {
            throw AgentRuntimeError.operationFailed("MODEL_BASE_URL_INVALID: Base URL 必须是无凭据、query 和 fragment 的 HTTPS URL")
        }
        guard !normalizedModel.isEmpty, normalizedModel.count <= 160 else {
            throw AgentRuntimeError.operationFailed("MODEL_ID_INVALID")
        }
        baseURL = url
        model = normalizedModel
        self.host = host
        sendsReasoningEffort = host == "api.moonshot.cn"
    }
}

struct ModelEndpointStore {
    private let defaults = UserDefaults.standard

    func load() -> ModelEndpoint {
        (try? ModelEndpoint(
            baseURL: defaults.string(forKey: "model-base-url") ?? ModelEndpoint.defaultBaseURL,
            model: defaults.string(forKey: "model-id") ?? ModelEndpoint.defaultModel
        )) ?? .default
    }

    @discardableResult
    func save(baseURL: String, model: String) throws -> ModelEndpoint {
        let endpoint = try ModelEndpoint(baseURL: baseURL, model: model)
        defaults.set(endpoint.baseURL.absoluteString, forKey: "model-base-url")
        defaults.set(endpoint.model, forKey: "model-id")
        return endpoint
    }
}

@MainActor
final class OpenAICompatibleClient {
    private let apiKey: String
    let endpoint: ModelEndpoint

    init(
        apiKey: String,
        endpoint: ModelEndpoint = .default
    ) {
        self.apiKey = apiKey
        self.endpoint = endpoint
    }

    func complete(messages: [[String: Any]], tools: [[String: Any]]) async throws -> [String: Any] {
        var request = URLRequest(url: endpoint.baseURL.appending(path: "chat/completions"))
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = [
            "model": endpoint.model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
        ]
        if endpoint.sendsReasoningEffort { body["reasoning_effort"] = "low" }
        let taskID = benchmarkTaskID(messages)
        var format: [String: Any]?
        if let taskID {
            let successfulCalls = successfulToolCalls(messages)
            let readyForFinal = taskID == "C1"
                ? hasC1CompletionEvidence(successfulCalls)
                : !successfulCalls.isEmpty
            format = readyForFinal ? responseFormat(taskID: taskID) : nil
            if let format { body["response_format"] = format }
        }
        NSLog(
            "MobileAgentPi model request endpointHost=%@ model=%@ benchmarkTask=%@ responseFormat=%@",
            endpoint.host,
            endpoint.model,
            taskID ?? "none",
            format?["type"] as? String ?? "text"
        )
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AgentRuntimeError.operationFailed("MODEL_INVALID_RESPONSE")
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard (200...299).contains(http.statusCode) else {
            let error = json["error"] as? [String: Any]
            let message = error?["message"] as? String ?? "HTTP \(http.statusCode)"
            throw AgentRuntimeError.operationFailed("MODEL_API_ERROR: \(message)")
        }
        return json
    }

    private func benchmarkTaskID(_ messages: [[String: Any]]) -> String? {
        for message in messages where message["role"] as? String == "user" {
            let content = messageText(message["content"])
            guard content.hasPrefix("[BENCH:"),
                  let closing = content.firstIndex(of: "]") else { continue }
            return String(content[content.index(content.startIndex, offsetBy: 7)..<closing])
        }
        return nil
    }

    private func messageText(_ content: Any?) -> String {
        if let text = content as? String { return text }
        guard let parts = content as? [[String: Any]] else { return "" }
        return parts.compactMap { part in
            part["type"] as? String == "text" ? part["text"] as? String : nil
        }.joined(separator: "\n")
    }

    private typealias SuccessfulToolCall = (name: String, arguments: [String: Any])

    private func successfulToolCalls(_ messages: [[String: Any]]) -> [SuccessfulToolCall] {
        var successfulIDs = Set<String>()
        for message in messages where message["role"] as? String == "tool" {
            guard let id = message["tool_call_id"] as? String,
                  let data = messageText(message["content"]).data(using: .utf8),
                  let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  result["error"] == nil else { continue }
            successfulIDs.insert(id)
        }
        var result: [SuccessfulToolCall] = []
        for message in messages {
            guard let calls = message["tool_calls"] as? [[String: Any]] else { continue }
            for call in calls {
                guard let id = call["id"] as? String, successfulIDs.contains(id),
                      let function = call["function"] as? [String: Any],
                      let name = function["name"] as? String,
                      let encoded = function["arguments"] as? String,
                      let data = encoded.data(using: .utf8),
                      let arguments = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                result.append((name, arguments))
            }
        }
        return result
    }

    private func hasC1CompletionEvidence(_ calls: [SuccessfulToolCall]) -> Bool {
        let searchedByURL = calls.contains { call in
            guard call.name == "device_invoke",
                  call.arguments["capability"] as? String == "open_url",
                  let params = call.arguments["params"] as? [String: Any],
                  let url = params["url"] as? String,
                  let items = URLComponents(string: url)?.queryItems else { return false }
            return items.contains { ["q", "query", "keyword", "wd"].contains($0.name.lowercased()) }
        }
        let searchedByInput = calls.contains {
            $0.name == "device_act" && $0.arguments["action"] as? String == "input"
        }
        let click = calls.contains {
            $0.name == "device_act" && $0.arguments["action"] as? String == "click"
        }
        let scroll = calls.contains {
            $0.name == "device_act" && ["scroll", "swipe"].contains($0.arguments["action"] as? String)
        }
        return (searchedByURL || searchedByInput) && click && scroll
    }

    private func responseFormat(taskID: String) -> [String: Any] {
        guard taskID == "C1" else { return ["type": "json_object"] }
        let itemSchema: [String: Any] = [
            "type": "object",
            "additionalProperties": false,
            "properties": [
                "title": ["type": "string"],
                "snippet": ["type": "string"],
            ],
            "required": ["title", "snippet"],
        ]
        let detailSchema: [String: Any] = [
            "type": "object",
            "additionalProperties": false,
            "properties": [
                "title": ["type": "string"],
                "summary": ["type": "string"],
            ],
            "required": ["title", "summary"],
        ]
        let schema: [String: Any] = [
            "type": "object",
            "additionalProperties": false,
            "properties": [
                "query": ["type": "string"],
                "items": ["type": "array", "minItems": 5, "maxItems": 5, "items": itemSchema],
                "detail": detailSchema,
                "scrolled": ["type": "boolean"],
            ],
            "required": ["query", "items", "detail", "scrolled"],
        ]
        return [
            "type": "json_schema",
            "json_schema": ["name": "mobile_agent_c1_result", "strict": true, "schema": schema],
        ]
    }
}
