# Chess Assist (Android)

Point the phone's camera at a physical chess board. The app reads the position on-device,
works out each move played, asks Stockfish for the best reply, and tells you by voice and on screen.

Intended for casual games where everyone at the board knows it's being used. Don't use it in
rated or tournament play, or on an opponent who hasn't agreed.

## Status: scaffold, not yet built or tested

This was written without an Android SDK, so it has **never been compiled or run**. Expect to fix
small compile errors on first build. Two parts are not included and you must add them:

1. **`app/src/main/assets/chess_pieces.tflite`**: the on-device piece detector.
   - YOLOv8-style, 12 classes in this order: white P N B R Q K, then black p n b r q k.
   - Input `[1, S, S, 3]` float32 0..1. Output `[1, 16, N]` or `[1, N, 16]` (4 box values + 12 class scores).
   - Train or fine-tune one on a public chess-pieces dataset (e.g. from Roboflow Universe), then
     export with `yolo export model=best.pt format=tflite`. The class order must match
     `TfliteBoardRecognizer.CLASSES`.
2. **`app/src/main/jniLibs/arm64-v8a/libstockfish.so`**: an ARM64 Android build of Stockfish. The GitHub
   Actions workflow builds one automatically if this file is absent.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds a debug APK on every push: download it from the
run's artifacts. Or open the folder in Android Studio and run on a physical arm64 phone (Android 8+).

## How it works

| File | Job |
|---|---|
| `MainActivity.kt` | Camera preview with a square guide, controls, on-screen board with arrow |
| `Recognizer.kt` | TFLite detector, then a piece per square (base of each box decides the square) |
| `MoveTracker.kt` | Commits only a *legal* move that best explains the frame, after 3 agreeing frames |
| `UciEngine.kt` | Runs the bundled Stockfish over UCI |
| `Speech.kt` | "Knight to f3", "Bishop takes e5", "Castle kingside", a-file spoken as "ay" |
| `GameViewModel.kt` | Game loop: confirm start position, track moves, suggest on your turn |

## Using it

Prop the phone above the board so the whole board fills the white square, with your pieces at the
bottom of the frame (use "I'm Black" if you sit on black's side). Press Start. You'll hear "Ready", then the
best move on your turns. Moves are suggested only; play them on the board yourself.

Starts from the normal opening position. To join a game midway, pass a FEN to `MoveTracker(startFen)`.

## Known limits

- The board must be roughly square-on and fill the guide; there is no automatic corner detection.
- Accuracy depends entirely on the detector model you train.
- Camera is read about once a second.
