import Foundation

enum MessageRole: String, Codable, Sendable {
    case user
    case agent
    case status
    case error
}

struct ChatMessage: Identifiable, Codable, Sendable {
    let id: UUID
    let role: MessageRole
    let text: String
    let timestamp: Date

    init(id: UUID = UUID(), role: MessageRole, text: String, timestamp: Date = Date()) {
        self.id = id
        self.role = role
        self.text = text
        self.timestamp = timestamp
    }
}

enum AgentStatus: String, Sendable {
    case idle
    case thinking
    case acting
    case waitingApproval
    case complete
    case error
}

struct ApprovalRequest: Identifiable, Sendable {
    let id = UUID()
    let description: String
}

struct ToolExecution: Sendable {
    let json: String
}

enum AgentRuntimeError: LocalizedError {
    case invalidArguments(String)
    case unsupported(String)
    case operationFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidArguments(let message): message
        case .unsupported(let message): message
        case .operationFailed(let message): message
        }
    }
}

extension Dictionary where Key == String, Value == Any {
    func jsonString() throws -> String {
        let data = try JSONSerialization.data(withJSONObject: self, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}

