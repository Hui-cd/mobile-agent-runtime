import Foundation
import SafariServices
import UIKit

@MainActor
final class IOSDeviceRuntime {
    private var lastInvocation: [String: Any]?

    func execute(name: String, arguments: [String: Any]) async throws -> ToolExecution {
        switch name {
        case "device_observe":
            return try observe()
        case "device_act":
            throw AgentRuntimeError.unsupported(
                "IOS_GLOBAL_UI_ACTION_UNAVAILABLE: iOS 不允许第三方 App 读取或点击其他 App 的 UI。请改用 device_invoke、App Intent 或用户安装的 Shortcut。"
            )
        case "device_invoke":
            return try await invoke(arguments)
        default:
            throw AgentRuntimeError.unsupported("UNKNOWN_TOOL: \(name)")
        }
    }

    func observe() throws -> ToolExecution {
        let state: String = switch UIApplication.shared.applicationState {
        case .active: "active"
        case .inactive: "inactive"
        case .background: "background"
        @unknown default: "unknown"
        }
        var observation: [String: Any] = [
            "observed_at": ISO8601DateFormatter().string(from: Date()),
            "platform": "ios",
            "application_state": state,
            "device": [
                "model": UIDevice.current.model,
                "system_name": UIDevice.current.systemName,
                "system_version": UIDevice.current.systemVersion,
                "locale": Locale.current.identifier,
                "time_zone": TimeZone.current.identifier,
            ],
            "capabilities": [
                "global_ui_observe": false,
                "global_ui_control": false,
                "open_url": true,
                "deep_link": true,
                "navigate": true,
                "dial": true,
                "share_sheet": true,
                "run_shortcut": true,
                "app_intents": true,
            ],
            "limitations": [
                "无法获取其他 App 的前台包名、截图或 UI Tree",
                "无法模拟跨 App 点击、输入、返回或 Home",
                "跨 App 自动化必须由 URL Scheme、Universal Link、App Intent 或 Shortcut 提供",
            ],
        ]
        if let lastInvocation { observation["last_invoke"] = lastInvocation }
        return ToolExecution(json: try observation.jsonString())
    }

    private func invoke(_ arguments: [String: Any]) async throws -> ToolExecution {
        guard let capability = arguments["capability"] as? String else {
            throw AgentRuntimeError.invalidArguments("MISSING_CAPABILITY")
        }
        let params = arguments["params"] as? [String: Any] ?? [:]
        let url: URL

        switch capability {
        case "open_url", "deep_link":
            url = try requiredURL(params, key: "url")
        case "open_app":
            url = try appURL(params)
        case "navigate":
            guard let destination = params["destination"] as? String, !destination.isEmpty else {
                throw AgentRuntimeError.invalidArguments("MISSING_DESTINATION")
            }
            var components = URLComponents(string: "https://maps.apple.com/")!
            components.queryItems = [URLQueryItem(name: "q", value: destination)]
            url = components.url!
        case "dial":
            guard let number = params["number"] as? String, !number.isEmpty else {
                throw AgentRuntimeError.invalidArguments("MISSING_PHONE_NUMBER")
            }
            guard let value = URL(string: "tel:\(number.filter { $0.isNumber || $0 == "+" })") else {
                throw AgentRuntimeError.invalidArguments("INVALID_PHONE_NUMBER")
            }
            url = value
        case "open_settings":
            url = URL(string: UIApplication.openSettingsURLString)!
        case "run_shortcut":
            url = try shortcutURL(params)
        case "share":
            guard let text = params["text"] as? String, !text.isEmpty else {
                throw AgentRuntimeError.invalidArguments("MISSING_SHARE_TEXT")
            }
            try presentShareSheet(items: [text])
            lastInvocation = ["capability": capability, "result": "share_sheet_presented"]
            return try observe()
        default:
            throw AgentRuntimeError.unsupported("UNSUPPORTED_IOS_CAPABILITY: \(capability)")
        }

        let opened = if ["http", "https"].contains(url.scheme?.lowercased()) {
            presentWebPage(url)
        } else {
            await open(url)
        }
        guard opened else {
            throw AgentRuntimeError.operationFailed("IOS_OPEN_FAILED: \(url.absoluteString)")
        }
        lastInvocation = [
            "capability": capability,
            "url": url.absoluteString,
            "result": "request_accepted_by_system",
            "externally_verified": false,
        ]
        return try observe()
    }

    private func requiredURL(_ params: [String: Any], key: String) throws -> URL {
        guard let raw = params[key] as? String, let url = URL(string: raw), url.scheme != nil else {
            throw AgentRuntimeError.invalidArguments("INVALID_OR_MISSING_URL")
        }
        return url
    }

    private func appURL(_ params: [String: Any]) throws -> URL {
        if let raw = params["url"] as? String, let url = URL(string: raw), url.scheme != nil { return url }
        switch (params["app"] as? String)?.lowercased() {
        case "maps", "apple maps", "地图": return URL(string: "maps://")!
        case "shortcuts", "快捷指令": return URL(string: "shortcuts://")!
        case "settings", "设置": return URL(string: UIApplication.openSettingsURLString)!
        default:
            throw AgentRuntimeError.invalidArguments(
                "IOS_OPEN_APP_REQUIRES_URL_SCHEME: 请提供 params.url；iOS 不能通过 bundle id 任意启动第三方 App。"
            )
        }
    }

    private func shortcutURL(_ params: [String: Any]) throws -> URL {
        guard let name = params["name"] as? String, !name.isEmpty else {
            throw AgentRuntimeError.invalidArguments("MISSING_SHORTCUT_NAME")
        }
        var components = URLComponents(string: "shortcuts://run-shortcut")!
        var query = [URLQueryItem(name: "name", value: name)]
        if let input = params["input"] as? String, !input.isEmpty {
            query.append(URLQueryItem(name: "input", value: "text"))
            query.append(URLQueryItem(name: "text", value: input))
        }
        components.queryItems = query
        guard let url = components.url else { throw AgentRuntimeError.invalidArguments("INVALID_SHORTCUT") }
        return url
    }

    private func open(_ url: URL) async -> Bool {
        await withCheckedContinuation { continuation in
            UIApplication.shared.open(url, options: [:]) { continuation.resume(returning: $0) }
        }
    }

    private func presentWebPage(_ url: URL) -> Bool {
        guard let presenter = foregroundPresenter() else { return false }
        presenter.present(SFSafariViewController(url: url), animated: true)
        return true
    }

    private func presentShareSheet(items: [Any]) throws {
        guard let presenter = foregroundPresenter() else {
            throw AgentRuntimeError.operationFailed("NO_FOREGROUND_WINDOW_FOR_SHARE")
        }
        presenter.present(UIActivityViewController(activityItems: items, applicationActivities: nil), animated: true)
    }

    private func foregroundPresenter() -> UIViewController? {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return nil }
        var presenter = root
        while let presented = presenter.presentedViewController { presenter = presented }
        return presenter
    }
}
