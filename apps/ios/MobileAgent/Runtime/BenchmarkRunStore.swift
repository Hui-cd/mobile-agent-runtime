import Foundation
import UIKit

@MainActor
final class BenchmarkRunStore {
    private let prompt: String
    private let metrics: PiRunMetrics
    private let runID = UUID().uuidString
    private let startedAt = ISO8601DateFormatter().string(from: Date())
    private let taskID: String
    private let benchmarkCohort: String
    private let attempt: Int
    private let model: String
    private let modelEndpointHost: String?

    init(
        prompt: String,
        metrics: PiRunMetrics,
        model: String = "kimi-k3",
        modelEndpointHost: String? = nil
    ) {
        self.prompt = prompt
        self.metrics = metrics
        self.model = model
        self.modelEndpointHost = modelEndpointHost
        taskID = Self.taskID(from: prompt)
        benchmarkCohort = Self.cohortID(from: prompt)
        let key = "benchmark-attempt-\(taskID)"
        attempt = UserDefaults.standard.integer(forKey: key) + 1
        UserDefaults.standard.set(attempt, forKey: key)
        Self.reconcileInterrupted()
        writePending()
    }

    func complete(answer: String, notes: String? = nil) {
        let result = parseResult(answer)
        let validation = validateResult(result)
        persist(
            status: validation.status,
            result: result,
            failureCode: validation.failureCode,
            failureStage: validation.failureCode == nil ? nil : "adjudication",
            notes: validation.notes ?? notes
        )
    }

    func fail(_ error: Error) {
        let stage = metrics.failureStage ?? "agent"
        persist(
            status: "failed",
            failureCode: error.localizedDescription.split(separator: ":").first.map(String.init) ?? "ERROR",
            failureStage: stage,
            notes: error.localizedDescription,
            crash: stage == "runtime:webview_renderer"
        )
    }

    func cancel() {
        persist(status: "cancelled", failureCode: "USER_CANCELLED", failureStage: metrics.failureStage ?? "agent")
    }

    static func fileURL() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appending(path: "benchmarks", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appending(path: "runs.jsonl")
    }

    private func persist(
        status: String,
        result: Any = NSNull(),
        failureCode: String? = nil,
        failureStage: String? = nil,
        notes: String? = nil,
        crash: Bool = false
    ) {
        metrics.finishForegroundInterval()
        let record = makeRecord(
            status: status,
            result: result,
            failureCode: failureCode,
            failureStage: failureStage,
            notes: notes,
            crash: crash
        )
        do {
            try Self.append(record)
            try clearPending()
        } catch {
            NSLog("MobileAgent benchmark persist failed: %@", error.localizedDescription)
        }
    }

    private func makeRecord(
        status: String,
        result: Any = NSNull(),
        failureCode: String? = nil,
        failureStage: String? = nil,
        notes: String? = nil,
        crash: Bool
    ) -> [String: Any] {
        #if targetEnvironment(simulator)
        let environment = "simulator"
        let devOnly = true
        #else
        let environment = "physical_device"
        let devOnly = false
        #endif
        let defaultLoginState = ["M1", "X1", "D1", "W1"].contains(taskID) ? "unknown" : "not_applicable"
        return [
            "schema_version": 1,
            "run_id": runID,
            "started_at": startedAt,
            "platform": "ios",
            "os_version": UIDevice.current.systemVersion,
            "device_model": UIDevice.current.model,
            "environment": environment,
            "dev_only": devOnly,
            "runtime_version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
            "agent_runtime": "@mariozechner/pi-agent-core@0.73.1",
            "runtime_carrier": "wkwebview",
            "model": model,
            "model_endpoint_host": modelEndpointHost ?? NSNull(),
            "backend": "ios_app_intent_public",
            "task_id": taskID,
            "benchmark_cohort": benchmarkCohort,
            "attempt": attempt,
            "prompt": prompt,
            "status": status,
            "duration_ms": metrics.durationMilliseconds,
            "agent_turns": metrics.agentTurns,
            "model_calls": metrics.modelCalls,
            "tool_calls": metrics.toolCalls,
            "manual_takeovers": metrics.approvalInteractions,
            "foreground_interrupt_ms": metrics.foregroundInterruptMilliseconds,
            "crash": crash,
            "login_state_before": defaultLoginState,
            "login_state_after": defaultLoginState,
            "login_lost": NSNull(),
            "permission_lost": NSNull(),
            "observation_failures": metrics.observationFailures,
            "action_failures": metrics.actionFailures,
            "target_reference": metrics.lastTargetReference ?? NSNull(),
            "app_version": NSNull(),
            "result": result,
            "evidence": metrics.evidence,
            "failure_code": failureCode ?? NSNull(),
            "failure_stage": failureStage ?? NSNull(),
            "notes": notes ?? NSNull(),
        ]
    }

    private func writePending() {
        do {
            let record = makeRecord(
                status: "failed",
                failureCode: "RUN_INTERRUPTED",
                failureStage: "process",
                notes: "Run started but did not reach a terminal recorder write; counters may be incomplete.",
                crash: true
            )
            let data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
            try data.write(to: Self.pendingURL(), options: .atomic)
        } catch {
            NSLog("MobileAgent benchmark pending write failed: %@", error.localizedDescription)
        }
    }

    private func clearPending() throws {
        let url = try Self.pendingURL()
        guard let data = try? Data(contentsOf: url),
              let record = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              record["run_id"] as? String == runID else { return }
        try FileManager.default.removeItem(at: url)
    }

    static func reconcileInterrupted() {
        do {
            let pending = try pendingURL()
            guard FileManager.default.fileExists(atPath: pending.path) else { return }
            let data = try Data(contentsOf: pending)
            guard var record = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let runID = record["run_id"] as? String else {
                try FileManager.default.removeItem(at: pending)
                return
            }
            let runs = try fileURL()
            let alreadyFinalized = (try? String(contentsOf: runs, encoding: .utf8))?
                .contains("\"run_id\":\"\(runID)\"") == true
            if !alreadyFinalized {
                let started = (record["started_at"] as? String).flatMap { ISO8601DateFormatter().date(from: $0) }
                record["duration_ms"] = max(0, Int(Date().timeIntervalSince(started ?? Date()) * 1000))
                record["status"] = "failed"
                record["crash"] = true
                record["failure_code"] = "RUN_INTERRUPTED"
                record["failure_stage"] = "process"
                record["notes"] = "Recovered an unfinished run after process termination; counters may be incomplete."
                try append(record)
            }
            try FileManager.default.removeItem(at: pending)
        } catch {
            NSLog("MobileAgent benchmark reconciliation failed: %@", error.localizedDescription)
        }
    }

    private static func pendingURL() throws -> URL {
        try fileURL().deletingLastPathComponent().appending(path: "pending-run.json")
    }

    private static func append(_ record: [String: Any]) throws {
        let url = try fileURL()
        var data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
        data.append(0x0A)
        if !FileManager.default.fileExists(atPath: url.path) {
            try data.write(to: url, options: .atomic)
        } else {
            let handle = try FileHandle(forWritingTo: url)
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            try handle.close()
        }
    }

    private func parseResult(_ answer: String?) -> Any {
        guard let answer, let data = answer.data(using: .utf8) else { return NSNull() }
        return (try? JSONSerialization.jsonObject(with: data)) ?? ["raw_text": answer]
    }

    private func validateResult(_ result: Any) -> Validation {
        if taskID == "ad_hoc" { return Validation(status: "completed_unverified") }
        guard let json = result as? [String: Any] else {
            return Validation(status: "failed", failureCode: "TASK_RESULT_INVALID", notes: "final 不是 JSON object")
        }
        let valid: Bool
        switch taskID {
        case "L1":
            valid = json["request_accepted"] as? Bool == true && metrics.lastTargetReference != nil
        #if DEBUG
        case "R1":
            valid = json["request_accepted"] as? Bool == true && metrics.lastTargetReference != nil
        #endif
        case "M1", "X1", "D1", "W1":
            valid = false // Public iOS backend cannot claim arbitrary third-party UI extraction.
        default:
            valid = false
        }
        return valid
            ? Validation(status: "success")
            : Validation(status: "failed", failureCode: "TASK_RESULT_INVALID", notes: "未达到 \(taskID) 的公开 iOS 能力与结构门槛")
    }

    private struct Validation {
        let status: String
        var failureCode: String?
        var notes: String?

        init(status: String, failureCode: String? = nil, notes: String? = nil) {
            self.status = status
            self.failureCode = failureCode
            self.notes = notes
        }
    }

    private static func taskID(from prompt: String) -> String {
        let prefix = "[BENCH:"
        guard prompt.hasPrefix(prefix),
              let closing = prompt.firstIndex(of: "]") else { return "ad_hoc" }
        let candidate = String(prompt[prompt.index(prompt.startIndex, offsetBy: prefix.count)..<closing])
        guard candidate.range(of: "^[A-Z][0-9]+$", options: .regularExpression) != nil else { return "ad_hoc" }
        return candidate
    }

    private static func cohortID(from prompt: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: #"\[COHORT:([A-Za-z0-9._-]{1,64})\]"#),
              let match = regex.firstMatch(in: prompt, range: NSRange(prompt.startIndex..., in: prompt)),
              let range = Range(match.range(at: 1), in: prompt) else { return "unspecified" }
        return String(prompt[range])
    }
}
