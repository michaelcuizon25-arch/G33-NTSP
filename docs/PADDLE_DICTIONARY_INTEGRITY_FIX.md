# Paddle dictionary integrity failure: cross-platform checkout fix

The reported 49 device records all fail with
`Paddle asset integrity check failed: ocr/paddle/ppocrv5_en_dict.txt`.
This exception occurs while reading/verifying raw asset bytes, before UTF-8
character parsing and before ONNX session initialization. Repeated dictionary
construction attempts can therefore fail on every line even though CRNN still
produces notes. It is not evidence that ONNX inference itself failed.

## Investigation and reproduced cause

The official dictionary in the Git index and Linux working tree is UTF-8 without
a BOM, with 436 LF-terminated entries, 1,416 bytes, SHA-256:

`e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`

This exactly matches `PaddleModelAssets.DICTIONARY_SHA256` and provenance metadata.
Both pre-fix Linux-built APKs also contain those exact bytes. Android's asset
loader hashes `readBytes()` before decoding; UTF-8 parsing is not changing the
bytes used for verification. ZIP compression does not modify the uncompressed
asset. The digest-to-hex code and expected hash are consistent with the official
asset; there is no reason to change the hash.

There was no `.gitattributes` rule protecting this byte-verified text asset.
Using the actual index with `git -c core.autocrlf=true checkout-index` reproduced
Windows line-ending conversion: 436 CRLF endings, 1,852 bytes, SHA-256:

`8459e5659185f87d62195cb495f2677493bade134182bfb8c54bf21757b28cdf`

Converting just those CRLF endings back to LF produces the exact official bytes.
Thus a normal Windows checkout can silently change the vocabulary file's binary
representation while leaving its visible vocabulary intact; Gradle packages that
working-tree file and the strict runtime check correctly rejects it. Repeated
records from one APK all share the bad asset.

This reproduces the failure mechanism and fits the Windows/Android Studio setup.
The user's installed APK/49 records were not supplied to this workspace, so its
actual packaged dictionary hash must still be checked on the phone/Windows PC.
If that APK does not contain the reproduced CRLF bytes, retain its actual hash,
size, BOM/encoding and bytes for investigation rather than assuming CRLF or
changing a pinned hash.

## Smallest safe correction

Root `.gitattributes` now pins only this asset to `text eol=lf`:

```gitattributes
/app/src/main/assets/ocr/paddle/ppocrv5_en_dict.txt text eol=lf
```

The dictionary, its 436 official entries/order, expected hashes, ONNX model,
preprocessing, both CTC decoders, feature defaults and production code are unchanged.
No permissive fallback, runtime newline normalization, alternate-hash acceptance
or hash regeneration is introduced. Corrupt/edited assets still fail validation.

`tools/test_paddle_asset_checkout.py` checks real repository checkout with
`core.autocrlf=true` and `false`, both with `core.eol=crlf`, into temporary directories.
Both must retain the exact official bytes, and the test must leave the source/index
unchanged. A focused Android test checks the actual target APK dictionary through
the existing strict loader, byte count, no CR/BOM, and blank/dictionary/space
class ordering before any model inference.

## Windows and phone verification

Update the Windows `feature/ocr-accuracy-improvements` checkout to include this
fix before following these steps. Preserve any local changes when updating.

1. Stop the current app and preserve the old JSONL records. To prove the cause for
   the *installed* APK, export it before replacing it. In PowerShell, with your
   SDK path corrected if necessary:

   ```powershell
   $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
   $line = & $adb shell pm path com.example.note2snap |
       Where-Object { $_.Trim() -match '/base\.apk$' } | Select-Object -First 1
   if (-not $line) { throw 'Installed base APK not found' }
   $remoteApk = $line.Trim().Substring(8)
   & $adb pull $remoteApk .\before-fix-base.apk
   if ($LASTEXITCODE -ne 0) { throw 'APK export failed' }
   py -3 -c "import zipfile,hashlib; z=zipfile.ZipFile('before-fix-base.apk'); b=z.read('assets/ocr/paddle/ppocrv5_en_dict.txt'); print('bytes=',len(b),'sha256=',hashlib.sha256(b).hexdigest(),'CRLF=',b.count(bytes([13,10])),'BOM=',b[:3].hex())"
   ```

   1,852 bytes / 436 CRLF / hash starting `8459e565` confirms the reproduced cause.
   A BOM, a different vocabulary, stale APK or different encoding requires a
   separate diagnosis. Archive those bytes/records; do not bypass integrity.
2. Verify the new attribute, then refresh **only** the dictionary from the index.
   This is necessary because adding attributes may not rewrite a file already
   checked out with CRLF. Guard against discarding staged/unstaged content edits:

   ```powershell
   git branch --show-current
   git check-attr text eol -- app/src/main/assets/ocr/paddle/ppocrv5_en_dict.txt
   git diff --exit-code HEAD -- app/src/main/assets/ocr/paddle/ppocrv5_en_dict.txt
   if ($LASTEXITCODE -ne 0) { throw 'Dictionary has content edits; preserve and review them first' }
   git checkout-index --force -- app/src/main/assets/ocr/paddle/ppocrv5_en_dict.txt
   if ($LASTEXITCODE -ne 0) { throw 'Dictionary checkout failed' }
   Get-FileHash .\app\src\main\assets\ocr\paddle\ppocrv5_en_dict.txt -Algorithm SHA256
   ```

   Attribute output must include `text: set` and `eol: lf`. The branch must be
   `feature/ocr-accuracy-improvements`. File hash must be the full official
   `e025a66d...24e70d6` value above. Do not change any expected/model hash.
3. Build/install a fresh comparison debug APK. With local Gradle 8.5 on PATH:

   ```powershell
   gradle.bat :app:clean :app:assembleDebug -PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
   if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
   py -3 -c "import zipfile,hashlib; z=zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk'); b=z.read('assets/ocr/paddle/ppocrv5_en_dict.txt'); h=hashlib.sha256(b).hexdigest(); print(len(b),h); assert h=='e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6'"
   if ($LASTEXITCODE -ne 0) { throw 'APK dictionary check failed' }
   & $adb install --no-incremental -r .\app\build\outputs\apk\debug\app-debug.apk
   if ($LASTEXITCODE -ne 0) { throw 'Installation failed; preserve app data' }
   ```

   In Android Studio, use the Stage 3C Gradle run configuration with the same
   properties. Do not then rebuild via an ordinary App Run configuration without
   them. The repository lacks runnable Gradle wrapper scripts; use local Gradle.
4. Run the new isolated packaged-dictionary test:

   ```powershell
   gradle.bat :app:connectedDebugAndroidTest -PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false -Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.PaddleRecognitionDeviceTest#dictionaryIntegrityAndCharacterOrderMatchOfficialAsset
   ```

   Then run the complete `PaddleRecognitionDeviceTest` class (omit the `#method`
   suffix) for native preprocessing/ORT parity. Stop if those checks fail; an
   asset fix does not prove there cannot be a separate native-runtime problem.
5. Enable `NOTE2SNAP_PERF`, launch via phone icon, and perform a new single scan:

   ```powershell
   & $adb shell setprop log.tag.NOTE2SNAP_PERF DEBUG
   & $adb shell am force-stop com.example.note2snap
   ```

   Capture the new `PADDLE_INITIALIZATION`, `PADDLE_INFERENCE` and
   `PADDLE_CTC_DECODE` stages. Collect only the new UUID comparison record(s) using
   Stage 3C baseline snapshots. Successful rows have a `paddle` object and no
   `paddleError`; do not re-score the 49 old failure records as fixed results.
   CRNN remains the note-generating engine. Preserve all old notes and records.

## Validation in this workspace

The Windows-style checkout regression passed with both Git settings after the
attribute fix. All 13 Python tooling tests pass. Full Gradle unit results remain 122 passed,
14 unchanged existing failures, and 1 skipped; no new failures. Debug, release
and Android-test APK assembly succeeded. The combined Gradle command returns
nonzero solely because of the existing unit failures. Both final application APKs
contain the exact 1,416-byte dictionary with the expected official SHA-256, and
the packaged ONNX digest is unchanged. `git diff --check` passes. No device is attached to this environment; the
focused dictionary and native tests are compiled here, with phone execution left
explicitly pending. These validation results were collected before the fix was
committed. No main-branch changes are part of this fix.
