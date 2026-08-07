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
    @Published var configurationError: String?

    private let keychain = KeychainStore()
    private var task: Task<Void, Never>?
    private var approvalContinuation: CheckedContinuation<Bool, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private init() {
        keyConfigured = keychain.load()?.isEmpty == false
    }

    func saveAPIKey(_ value: String) {
        do {
            try keychain.save(value.trimmingCharacters(in: .whitespacesAndNewlines))
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
            add(.error, "请先保存 Kimi API Key。")
            return
        }

        let history = messages.filter { $0.role == .user || $0.role == .agent }.suffix(12)
        isRunning = true
        status = .thinking
        currentStep = "正在读取 iOS Context"
        add(.user, normalized)
        beginBackgroundTime()

        task = Task { [weak self] in
            guard let self else { return }
            defer { self.finishBackgroundTime() }
            do {
                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                let engine = AgentEngine(
                    client: KimiClient(apiKey: key),
                    runtime: IOSDeviceRuntime(),
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
                let answer = try await engine.run(prompt: normalized, history: Array(history))
                add(.agent, answer)
                isRunning = false
                status = .complete
                currentStep = "任务完成"
                await notifyIfBackground(title: "Mobile Agent", body: answer)
            } catch is CancellationError {
                add(.status, "任务已停止")
                isRunning = false
                status = .idle
                currentStep = ""
            } catch {
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
