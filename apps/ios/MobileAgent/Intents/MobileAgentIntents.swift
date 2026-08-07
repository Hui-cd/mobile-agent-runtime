import AppIntents

struct RunMobileAgentIntent: AppIntent {
    static let title: LocalizedStringResource = "运行 Mobile Agent"
    static let description = IntentDescription("把一个任务发送给手机内的 Mobile Agent。")
    static let openAppWhenRun = true

    @Parameter(title: "任务", description: "希望 Mobile Agent 完成的事情")
    var prompt: String

    static var parameterSummary: some ParameterSummary {
        Summary("运行 \(\.$prompt)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        await AgentSession.shared.start(prompt)
        return .result(dialog: "任务已发送给 Mobile Agent")
    }
}

struct MobileAgentShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RunMobileAgentIntent(),
            phrases: [
                "Ask \(.applicationName)",
                "Run \(.applicationName)",
                "使用 \(.applicationName)",
            ],
            shortTitle: "运行 Agent",
            systemImageName: "sparkles"
        )
    }
}
