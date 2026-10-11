<div align="center">

# Mobile Agent Runtime

**Give your Android phone a task.**

An open-source agent that reads app screens and operates Android apps from your instructions.

[English](README.md) · [简体中文](README.zh-CN.md)

![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Alpha](https://img.shields.io/badge/status-alpha-orange)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

</div>

Describe the result you want. The agent opens an app, reads the screen, and selects the next action.
It checks the screen after each action and returns the result in the same conversation.

The agent runs on your phone. You supply a model API key for remote model requests.
With Shizuku, task apps can run on a separate display while you use the main screen.

> **Alpha:** Clock, Settings, and app installation have passed emulator checks. Physical phone compatibility and login handoff need further work.

## See it in use

<p align="center">
  <img src="docs/results/shizuku-ondevice-2026-10-12/ready-hidden-mode.png" width="240" alt="Mobile Agent with background mode enabled">
  &nbsp;&nbsp;
  <img src="docs/results/shizuku-ondevice-2026-10-12/clock-screenshot-10-1.jpg" width="240" alt="Clock on the separate display, with the stopwatch paused at 5:09.85">
</p>

<p align="center">App interface · Clock on the separate display</p>

Try these tasks:

```text
Open Clock. Start the stopwatch, then pause it. Tell me the elapsed time.

Open Settings. Search for bluetooth. Read the first three results, then return to the main settings page.

Download the official WeChat APK from the publisher URL I provide. Install it, but do not log in.
```

These tasks use the same tools. The runtime does not contain a separate action script for each app.
Clock tasks started and paused the stopwatch. Settings tasks entered a search term and returned to the main page.
An installation task downloaded the official WeChat APK and installed version 8.0.79.

## What you can do

- **Operate apps from a goal.** Open apps, select controls, enter text, scroll, and return to the previous page.
- **Read screens.** Use app controls and screenshots to inspect the current page.
- **Keep the main screen available.** Use Shizuku to run a task app on a separate display.
- **Download and install apps.** Download an APK, read its progress, check its package name, and install it through Shizuku.
- **Choose a model service.** Use an HTTPS service with OpenAI-compatible Chat Completions and tool calling.
- **Stop a task.** Use the stop button in the app or its task notification.

App support depends on the controls each app exposes. A task can stop when a page, permission, or account prevents progress.

## Quick start

### 1. Prepare your environment

| Item | Requirement |
|---|---|
| Phone or emulator | Android 11 or later for foreground mode |
| Background mode | Android 14 or later, with Shizuku running and authorized |
| Node.js | Version 22 or later |
| Java | JDK 17 or later |
| Android SDK | Platform API 37 and build tools 37.0.0 |
| Model service | HTTPS Chat Completions API with tool calling |

Install [Android Studio](https://developer.android.com/studio).
Use SDK Manager to install the required platform, build tools, and platform tools.
Set `ANDROID_HOME` to your SDK directory.
Add the SDK `platform-tools` directory to your `PATH`.

On macOS, Android Studio commonly uses these paths:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

On other systems, use the SDK and Java paths for your installation.

### 2. Build the app

```bash
git clone https://github.com/Hui-cd/mobile-agent-runtime.git
cd mobile-agent-runtime
npm ci
npm run android:build
```

The build creates this APK:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install the app

For a phone, enable USB debugging in Developer options.
Connect the phone to your computer.
Accept the USB debugging request on the phone.

For an emulator, create a device in Android Studio Device Manager.
Start the device before you install the APK.

```bash
adb devices
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

If ADB lists multiple devices, use `adb -s <serial> install -r <apk-path>` to select one.

### 4. Configure the app

1. Open **Mobile Agent**.
2. Enter your model API key.
3. Save the model settings.
4. Enable **Mobile Agent device control** in Android Accessibility settings.
5. Allow notifications for Mobile Agent.
6. Keep the screen on and the device unlocked.
7. Enter a task from the examples above.

The default model service is Kimi:

| Setting | Default |
|---|---|
| Base URL | `https://api.moonshot.cn/v1` |
| Model | `kimi-k3` |

Use the advanced model settings to change the base URL or model.
The app uses Android Keystore to encrypt the saved API key.
Model requests send task content, screen text, and requested screenshots to your selected model service.

Without background mode, the agent brings the task app to the main screen.
You can stop the task from its notification.

## Run tasks in background mode

Background mode places the task app on a separate Android display.
The agent reads and operates that display. You can use a different app on the main screen.

1. Install [Shizuku](https://shizuku.rikka.app/download/).
2. Start Shizuku with its [setup instructions](https://shizuku.rikka.app/guide/setup/).
3. Open Mobile Agent.
4. Open the app menu.
5. Select **开启后台执行（Shizuku）** to enable background mode.
6. Allow Mobile Agent to access Shizuku.
7. Select the background mode option again after the first authorization.
8. Enter your task.

The menu shows the active mode.
The app also shows a background mode message above the task input.
To use the main screen for tasks again, select **后台执行已开启 · 切回主屏** from the menu.

Task operations run on the device. They do not require a desktop control process after Shizuku starts.
Our emulator checks used ADB to start Shizuku. Wireless startup from the phone still needs a device check.

Keep the screen on and the device unlocked.
Do not operate the same task app on the main screen at the same time.
An app or a system dialog can still move to the main screen.
After a reboot, start Shizuku again if the service has stopped.

## App downloads

Give the agent an APK address from the app publisher and the expected package name.
For example, the WeChat package name is `com.tencent.mm`.

The agent can download the file and read its progress.
Before installation, it checks the APK package name against the requested name.
Installation requires background mode and Shizuku authorization.

A completed download does not mean installation is complete.
The runtime reports installation success only after the system installs and identifies the package.
It checks the package name, but it does not authenticate the publisher through a separate signature check.

Google Play can require a Google account.
Login and CAPTCHA handoff are not available in this version.

## Feishu and WeChat control

The intended workflow starts with a task in a chat. The phone performs the task and returns the result there.

| Entry point | Current support |
|---|---|
| Mobile Agent app | App tasks and results on the device |
| Feishu bot | Development bridge for Clock, status, and stop commands |
| WeChat chat | Planned |

The Feishu bridge runs on a computer and uses ADB to send tasks to the emulator.
It does not yet provide an independent phone connection.
Live Feishu message delivery still needs an account check.

To prepare a development bot:

1. Create a Feishu application with a bot.
2. Enable permissions to receive private messages and send bot messages.
3. Select the long connection option for events.
4. Subscribe to `im.message.receive_v1`.
5. Publish the application to your permitted users.

For development, copy `.env.feishu.example` to `.env.feishu`.
Enter your bot credentials and the permitted user ID.
Then start the bridge:

```bash
npm run channel:feishu:dev
```

Supported chat commands are `/clock`, `/status`, and `/stop`.
The bridge does not accept unrestricted app tasks.
Installing WeChat does not enable control through WeChat chats.

## No Android phone?

Use an emulator to start.
Create an Android 17 device in Android Studio Device Manager to match the environment used for current checks.
An Android 14 or later emulator can attempt background mode, but compatibility can differ.

Start your virtual device from Device Manager.
You can also list and start devices from the terminal:

```bash
emulator -list-avds
emulator -avd <avd-name> -no-snapshot-save
```

Install the APK with the quick start steps.
Configure the model and permissions inside the emulator.
An emulator requires its own app installation and account setup.
It does not contain the apps or accounts from your phone.

## How it works

```text
Your goal
   ↓
Agent on the phone ↔ Your model service
   ↓
Observe → Choose an action → Operate → Observe again
   ↓
Result or a request for your help
```

The runtime gives the model three tools:

| Tool | Function |
|---|---|
| `device_observe` | Read the current app, controls, and requested screenshot |
| `device_invoke` | Open an app or link, call system functions, or download and install an APK |
| `device_act` | Select a control, enter text, scroll, swipe, or return |

Android intents open apps and system pages.
Accessibility reads controls and performs interface actions.
In background mode, Shizuku creates the separate display and supplies the required privileged operations.

The same tool interface supports new apps without adding a model tool for each app.
New app workflows still require checks with the actual app.

## Development

Run these commands from the repository root:

```bash
npm test
npm run build
npm run android:test
npm run android:build
```

Current checks cover 56 Node tests and 52 Android unit tests.
Compilation and unit tests do not confirm compatibility with every phone or app.
Physical devices, login handoff, screen-off operation, and long tasks need further checks.

Current priorities are login and CAPTCHA handoff, independent chat transport, and physical phone compatibility.

## Common problems

| Problem | Action |
|---|---|
| The build cannot find the SDK | Set `ANDROID_HOME` to your Android SDK directory. |
| ADB shows `unauthorized` | Accept the USB debugging request on the phone. |
| The app cannot operate controls | Enable Mobile Agent in Accessibility settings. |
| Background mode cannot start | Start Shizuku and authorize Mobile Agent. |
| A task stops after the screen turns off | Turn on the screen. Unlock the device. Start a new task. |
| An app requests login or a CAPTCHA | Stop the task. Complete the account step yourself. |
| Dependency downloads are slow | Try the optional build mirror shown below. |

```bash
MOBILE_AGENT_USE_HUAWEI_MIRROR=1 npm run android:build
```

## Contribute

Report a reproducible problem through [GitHub Issues](https://github.com/Hui-cd/mobile-agent-runtime/issues).
Include the Android version, app version, task, and actual result.
See [CONTRIBUTING.md](CONTRIBUTING.md) for code contributions.
Report security problems through [SECURITY.md](SECURITY.md).

## License

Mobile Agent Runtime uses the [Apache License 2.0](LICENSE).
Third-party components retain their own licenses.
