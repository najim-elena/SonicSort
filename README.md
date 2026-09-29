# SonicSort

A small, simple, reliable Android application targeting Android 16 that organizes your audio files by their language and removes exact duplicates using SHA-256.

## How to build

Using Gradle wrapper:
`ash
./gradlew assembleDebug
`
The APK will be available in pp/build/outputs/apk/debug/app-debug.apk.

## How to obtain APK using GitHub Actions

When you push code to this repository (or run a manual workflow dispatch), the Build Android APK action will run. Go to the "Actions" tab in your GitHub repository, click on the workflow run, and download the pp-debug.apk artifact.

## How to install APK

Transfer the pp-debug.apk to your phone and open it. Ensure you have enabled "Install unknown apps" for your file manager.

## How to configure Gemini API key

Open the app, tap the "Settings" button, and paste your Gemini API key. The key is only kept in memory for the session and is used directly for requests.

## Required Android permissions

- READ_MEDIA_AUDIO (or READ_EXTERNAL_STORAGE on older devices)
- INTERNET (for querying Gemini API)
- Storage access / OS confirmation is required for actually deleting files.

## How language sorting works

The app uses GenerativeModel (Gemini API) to detect the language based on metadata such as the audio file's name. It scans the files using MediaStore.

## How duplicate verification works

The app groups files by exact byte size. Only if sizes match exactly, it reads the files and calculates their full SHA-256 hashes. If hashes are identical, they are marked as exact duplicates.
