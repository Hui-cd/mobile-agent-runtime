import Foundation

@MainActor
final class KimiClient {
    private let apiKey: String
    private let model: String
    private let baseURL: URL

    init(
        apiKey: String,
        model: String = "kimi-k3",
        baseURL: URL = URL(string: "https://api.moonshot.cn/v1")!
    ) {
        self.apiKey = apiKey
        self.model = model
        self.baseURL = baseURL
    }

    func complete(messages: [[String: Any]], tools: [[String: Any]]) async throws -> [String: Any] {
        var request = URLRequest(url: baseURL.appending(path: "chat/completions"))
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "model": model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "reasoning_effort": "low",
        ])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AgentRuntimeError.operationFailed("KIMI_INVALID_RESPONSE")
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard (200...299).contains(http.statusCode) else {
            let error = json["error"] as? [String: Any]
            let message = error?["message"] as? String ?? "HTTP \(http.statusCode)"
            throw AgentRuntimeError.operationFailed("KIMI_API_ERROR: \(message)")
        }
        return json
    }
}

