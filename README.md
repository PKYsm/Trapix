# 🔐 Trapix — Silent Intruder Catcher

**Trapix** captures a photo of anyone who enters the wrong password on your phone — silently, with location data & timestamp.

---

## ✨ Features

- 🔒 **Lock Types** — PIN, Pattern, Password (just like phone screen lock)
- 🖐 **Biometric Unlock** — Fingerprint / Face unlock support
- 📸 **Intruder Capture** — Wrong password → front/rear camera captures photo
- 📍 **Location + Metadata** — GPS coordinates & timestamp saved with each capture
- 🔔 **Notifications** — Instant alert when intruder captured (with photo preview)
- 🎛️ **Attempt Threshold** — Set capture after 1, 2, 3... wrong attempts
- 🗂️ **Gallery View** — Browse all intruder captures inside app
- 💾 **Save / Share / Delete** — Save to gallery, share image or info, delete
- 🙈 **Hide from Launcher** — Make app invisible from home screen
- 🌙 **Dark Theme** — Full dark UI

---

## 🚀 Build via GitHub Actions (No Android Studio needed)

### Step 1 — Fork/Push to GitHub
```bash
# In Termux:
pkg install git
git clone https://github.com/YOUR_USERNAME/Trapix.git  # or init new
cd Trapix
git add .
git commit -m "Initial commit"
git push origin main
```

### Step 2 — Enable Workflow Permissions
1. Go to your repo → **Settings** → **Actions** → **General**
2. Under "Workflow permissions" → select **"Read and write permissions"**
3. Click Save

### Step 3 — Get your APK
1. Go to **Actions** tab in your repo
2. Wait for build to complete (~5 mins)
3. Go to **Releases** → download `Trapix-*.apk`
4. Install on your phone!

---

## 📱 First Time Setup
1. Open app → choose lock type (PIN / Pattern / Password)
2. Set your lock
3. Grant permissions (Camera, Location, Notifications)
4. Done! App is now protected

---

## ⚙️ Settings
| Setting | Description |
|---|---|
| Biometric | Enable fingerprint/face unlock |
| Capture After N attempts | How many wrong tries before photo |
| Front/Rear Camera | Which camera to use |
| Notifications | Alert on intruder capture |
| Save to Gallery | Auto-save captures to phone gallery |
| Hide from Launcher | Remove app icon from home screen |

---

## 🛠️ Tech Stack
- Kotlin + Android Jetpack
- CameraX (silent capture)
- Room Database
- BiometricPrompt API
- Coroutines
- Material Design 3

---

**App Name:** Trapix  
**Min Android:** 8.0 (API 26)  
**Package:** `com.trapix.app`
