import AppIntents
import SwiftUI

@main
struct MobileAgentApp: App {
    init() {
        MobileAgentShortcuts.updateAppShortcutParameters()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
