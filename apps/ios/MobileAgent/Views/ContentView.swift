import AppIntents
import SwiftUI

struct ContentView: View {
    @StateObject private var session = AgentSession.shared
    @State private var apiKey = ""
    @State private var prompt = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                statusHeader
                if !session.keyConfigured { keyCard }
                capabilityCard
                conversation
                if let approval = session.approval { approvalCard(approval) }
                if session.isRunning { runningRow }
                composer
            }
            .padding(.horizontal, 16)
            .navigationTitle("Mobile Agent")
            .toolbar {
                if session.keyConfigured {
                    Menu {
                        ShortcutsLink()
                        Button("删除 API Key", role: .destructive) { session.deleteAPIKey() }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
    }

    private var statusHeader: some View {
        HStack(spacing: 8) {
            StatusPill(text: session.keyConfigured ? "Kimi 已连接" : "需要 API Key", ready: session.keyConfigured)
            StatusPill(text: "iOS 公开能力", ready: true)
            Spacer(minLength: 0)
        }
    }

    private var keyCard: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                SecureField("API Key", text: $apiKey)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .textFieldStyle(.roundedBorder)
                Text("Key 使用 iOS Keychain 保存，不写入源码或 iCloud Keychain。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let error = session.configurationError {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                Button("保存并连接") {
                    session.saveAPIKey(apiKey)
                    if session.keyConfigured { apiKey = "" }
                }
                .buttonStyle(.borderedProminent)
                .disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } label: {
            Label("连接 Kimi", systemImage: "key.fill")
        }
    }

    private var capabilityCard: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 6) {
                Text("支持 URL Scheme、Universal Link、系统地图、拨号、分享面板和 Shortcuts。")
                Text("iOS 不允许第三方 App 像 Android 无障碍服务那样读取或点击其他 App 的界面。")
                    .foregroundStyle(.secondary)
            }
            .font(.caption)
            .frame(maxWidth: .infinity, alignment: .leading)
        } label: {
            Label("iOS Runtime", systemImage: "iphone")
        }
    }

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(session.messages) { message in
                        MessageBubble(message: message).id(message.id)
                    }
                }
            }
            .onChange(of: session.messages.count) {
                guard let id = session.messages.last?.id else { return }
                withAnimation { proxy.scrollTo(id, anchor: .bottom) }
            }
        }
        .frame(maxHeight: .infinity)
    }

    private func approvalCard(_ approval: ApprovalRequest) -> some View {
        GroupBox("需要你的确认") {
            VStack(alignment: .leading, spacing: 10) {
                Text(approval.description)
                HStack {
                    Button("允许") { session.resolveApproval(true) }.buttonStyle(.borderedProminent)
                    Button("拒绝", role: .cancel) { session.resolveApproval(false) }.buttonStyle(.bordered)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .tint(.orange)
    }

    private var runningRow: some View {
        HStack {
            ProgressView()
            Text(session.currentStep).font(.subheadline)
            Spacer()
            Button("停止") { session.stop() }.buttonStyle(.bordered)
        }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("例如：打开地图搜索虹桥机场", text: $prompt, axis: .vertical)
                .lineLimit(1...4)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.send)
                .onSubmit(send)
            Button(action: send) {
                Image(systemName: "arrow.up.circle.fill").font(.title)
            }
            .disabled(!canSend)
        }
        .padding(.bottom, 8)
    }

    private var canSend: Bool {
        session.keyConfigured && !session.isRunning && !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func send() {
        guard canSend else { return }
        let value = prompt
        prompt = ""
        session.start(value)
    }
}

private struct StatusPill: View {
    let text: String
    let ready: Bool

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(ready ? Color.green.opacity(0.16) : Color.orange.opacity(0.18), in: Capsule())
    }
}

private struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == .user { Spacer(minLength: 40) }
            Text(message.text)
                .textSelection(.enabled)
                .padding(12)
                .background(background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            if message.role != .user { Spacer(minLength: 40) }
        }
    }

    private var background: Color {
        switch message.role {
        case .user: .blue.opacity(0.16)
        case .agent: .purple.opacity(0.14)
        case .status: .secondary.opacity(0.12)
        case .error: .red.opacity(0.16)
        }
    }
}

