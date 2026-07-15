# ww-md222 交接文件（給 Claude Code）

## 這個專案是什麼

一個 **LSPosed 模組**（Android / Kotlin），目標 App 是**鳴潮國際版 Wuthering Waves Global**
（package: `com.kurogame.wutheringwaves.global`）。

功能：攔截遊戲的支付請求，修改其中的地區欄位，把訂單地區改成台灣（TW）。
移植自一份 iOS 的 Substrate tweak（原始碼邏輯見下方「原始 iOS 邏輯」）。

專案已能透過 GitHub Actions 編譯出 APK，也能裝進 LSPosed，但**目前 hook 沒生效**
（下單時 hook 點沒被觸發）。需要你接手排查並修正 hook 點。

---

## 目前卡在哪（最重要）

**現況：模組能裝、遊戲能正常開、但下單時 patch 邏輯完全沒被呼叫。**

現在的 hook 點是 `org.json.JSONObject.put(String, Object)`（見 `MainHook.kt`）。
**強烈懷疑鳴潮的 JSON 序列化不走 `org.json.JSONObject`**，而是用 Gson / Fastjson /
Protobuf / 自訂序列化，所以這個 hook 點根本不會被呼叫到。

### 你要做的第一件事：確認 hook 點

請協助使用者用以下任一方式確認真正的序列化路徑：

1. **看 LSPosed 日誌**（過濾 `wwmd222`）：
   - 若完全沒有 `=== ww_md222 loaded ===` → 作用域沒勾對 / 模組沒啟用 / 沒重啟遊戲
   - 若有 loaded + `hook installed`，但下單時沒有 `storeArea` / `orderToken region @416` →
     **確認就是 hook 點問題**，`JSONObject.put` 沒被呼叫
   - 若有 `orderToken region @416: ... -> TW` → hook 有效，問題在伺服器端不認（另議）

2. **用 jadx / apktool 反編譯鳴潮 APK**，搜尋字串 `orderToken`、`storeArea`、`storeCurrency`，
   找出它們是在哪個類別、用什麼方式被組進請求的。這是最準的方法。

3. **改用更通用、對序列化庫不敏感的 hook 點**（建議方向，見下）。

### 建議的修正方向

真實封包的最終形態是（已驗證）：
```
POST body: sign=<md5>&data=<base64(JSON明文)>&pcode=G153&timestamp=<ts>
```
`data` 是「一整包 JSON 明文」做 base64。JSON 裡含 `orderToken`（本身又是
base64(zlib(960 hex))）、`storeCurrency`、`payId`、`pk` 等。

因此比 hook JSONObject 更可靠的攔截點是：

- **A. Hook Base64 編碼點**：hook `android.util.Base64.encode` / `encodeToString`，
  檢查被編碼的字串是否含 `"orderToken"`，若含則在明文階段先 patch 再放行。
  風險：Base64 全 App 高頻使用，需極輕量的前置過濾（先 `contains("orderToken")` 再說）。

- **B. Hook OkHttp 送出前**：hook `okhttp3.RequestBody` 建立處，或
  `okhttp3.Interceptor` 鏈，在 request body 字串層攔 `data=...`，
  對 base64 解碼 → 改 JSON → 重編碼。這是最終防線，對上游序列化庫完全不敏感。
  注意：若 body 已含 `sign=md5(...)`，改 data 後可能要重算 sign（需逆出 sign 演算法）。

- **C. 直接 hook 產生 orderToken / 組 data 的那個具體方法**（需先反編譯定位）。
  最精準、副作用最小，但要先找到方法簽名。

**優先順序建議：先做 2（反編譯定位）→ 再決定 A/B/C。** 不要盲目換 hook 點試錯。

### ⚠️ 也要排除「遊戲反 Xposed 偵測」

鳴潮（庫洛）這類遊戲常有反作弊。請先確認一個關鍵實驗：
**在 LSPosed 把本模組停用後，遊戲能不能正常開、正常下單？**
- 若停用後一切正常、啟用後只是「沒生效」→ 純 hook 點問題（如上）
- 若啟用本模組會導致閃退或封號風險 → 需考慮隱藏 LSPosed（如 Shamiko）、
  縮小作用域、或評估風險。使用者目前回報「能開、只是沒生效」，傾向前者。

---

## 已經驗證為正確的部分（不要改動這些）

以下用**真實鳴潮封包**（見 `samples/` 說明）實測過，是可靠事實：

1. **orderToken 結構**：`base64( zlib( 960 字元小寫 hex 文字 ) )`
   - base64 解碼後 537 bytes，zlib header = `78da`（標準 zlib, wbits=15）
   - zlib 解壓後是 **960 字元的 hex 文字**（不是 binary！）

2. **編碼格式關鍵坑**：原始 iOS 碼在重新編碼時把 hex 轉成 480 binary bytes 再壓縮，
   **這是錯的**。實測證明 App 壓縮的是「960 hex 文字本身」——
   壓縮 960 hex 文字得到 537 bytes，與原始封包**完全吻合（差距 0）**；
   壓縮 480 binary 得到 491 bytes（差 46）。
   → `OrderTokenPatcher.kt` 已修正為「直接壓縮 hex 文字」，**不要改回 binary**。

3. **地區欄位位置**：hex offset **416**（binary offset 208），長度 64 hex（32 bytes）。
   來源 iOS 註解如此標示，且用真實 MYR 封包驗證：覆蓋此位置後，
   其餘 bytes 完整保留、格式正確可還原。

4. **patch 邏輯**（`OrderTokenPatcher.patch()`）已用真實封包驗證通過：
   任意來源區 → 無條件把 offset 416 覆蓋成 `TW_BLOCK` → round-trip 正確。
   **這個檔案的演算法是對的，問題不在這裡，在 hook 點。**

---

## 原始 iOS 邏輯（供對照）

iOS tweak（Logos `.xm`）hook 的是 `NSMutableDictionary setObject:forKey:`，
在支付請求序列化「之前」攔截，做兩件事：

```
storeArea  → 設成空字串 ""
orderToken → patchOrderToken():
  1. base64 decode
  2. zlib inflate (wbits=15)
  3. 得到 960 字元 hex 文字
  4. 找 BRL 前綴 (只認巴西)         ← 安卓版已改成「固定 offset 416 無條件覆蓋」
  5. 換成 TW block (64 hex)
  6. [iOS 原碼: hex→480 binary]     ← 安卓版已修正: 直接壓 hex 文字
  7. zlib deflate
  8. base64 encode
```

安卓版與 iOS 的兩點差異（都是刻意且已驗證的正確修正）：
- iOS「只認 BRL」→ 安卓「固定位置無條件蓋 TW」（使用者要求任意區→TW）
- iOS「壓 binary」→ 安卓「壓 hex 文字」（實測證明後者才對）

---

## 關鍵常數

在 `app/src/main/java/com/ck/wwmd222/OrderTokenPatcher.kt`：

```kotlin
// 目標地區 TW block (32 bytes = 64 hex)
TW_BLOCK = "04fcba8ebfccc7ee1a2df7d3561ae27e2867dd17d120e5c5cf407d89ee3aae57"
// 地區欄位固定位置
REGION_OFFSET = 416
REGION_LEN = 64
```

**注意**：`TW_BLOCK` 這 64 hex 是從 iOS tweak 抄來的「台灣地區密文片段」。
若實測改成 TW 後伺服器不認，可能是：
(a) 這個 TW_BLOCK 樣本與當前遊戲版本不符 → 需重新抓一台台灣裝置的真實封包，
    解出 offset 416 的內容當新 TW_BLOCK；
(b) offset 416 不是當前版本的地區欄位位置 → 需比對「同帳號不同區」兩個封包，
    diff 出真正變動的 64 hex 位置。

---

## 真實封包樣本（排查用）

使用者提供的一筆真實封包（地區 MYR 馬來西亞），關鍵數據：

| 項目 | 值 |
|------|-----|
| storeCurrency | `MYR` |
| orderToken base64 長度 | 716 |
| base64 解碼後 | 537 bytes |
| zlib header | `78da` |
| 解壓後 | 960 字元 hex 文字 |
| offset 416 內容 (MYR) | `4d251b431d5a9acfcd25f20c71f91333c0796dee90056082486c19577a0d7f20` |

完整封包在使用者手上（`鳴潮正常封包.txt`）。請向使用者索取：
- **一台台灣區（TW）的真實封包** → 用來確認 TW_BLOCK 是否正確、offset 是否對
- 有 TW + 非TW 兩個封包就能 diff 出真正的地區欄位位置，這是最可靠的校準法。

### 驗證用 Python 片段（可直接跑）

```python
import base64, zlib
# token = 從封包 data 欄位 base64 解碼後的 JSON 裡取 orderToken 值（記得 \/ 還原成 /）
raw = base64.b64decode(token + "===")
assert raw[:2].hex() == "78da"          # 標準 zlib
hexstr = zlib.decompress(raw).decode('ascii')
assert len(hexstr) == 960               # 960 hex 文字
print("offset416:", hexstr[416:416+64]) # 地區欄位

# patch: 換 offset 416 成 TW，重新壓縮「hex 文字」（不是 binary！）
TW = "04fcba8ebfccc7ee1a2df7d3561ae27e2867dd17d120e5c5cf407d89ee3aae57"
patched = hexstr[:416] + TW + hexstr[416+64:]
new_token = base64.b64encode(zlib.compress(patched.encode('ascii'), 6)).decode()
```

---

## 專案結構

```
ww-md222/
├── .github/workflows/build.yml       # GitHub Actions: gradle 8.5 + JDK17 → APK
├── build.gradle.kts                  # AGP 8.1.4, Kotlin 1.9.22
├── settings.gradle.kts               # repos: google, mavenCentral, api.xposed.info, jitpack
├── app/
│   ├── build.gradle.kts              # compileOnly xposed api:82, debug 簽名
│   └── src/main/
│       ├── AndroidManifest.xml       # xposedmodule meta-data
│       ├── assets/xposed_init        # → com.ck.wwmd222.MainHook
│       ├── java/com/ck/wwmd222/
│       │   ├── MainHook.kt           # ★ hook 點在這，需要修
│       │   └── OrderTokenPatcher.kt  # ✓ 演算法已驗證正確，別動
│       └── res/values/
│           ├── strings.xml
│           └── arrays.xml            # xposedscope = 鳴潮 package
```

## 編譯 / 建置

GitHub Actions 自動編譯（push 即觸發）。本地編譯：
```bash
gradle assembleRelease   # 需 JDK 17；GitHub 上鎖定 gradle 8.5
# 產物: app/build/outputs/apk/release/app-release.apk
```

已驗證相容組合：**Gradle 8.5 + AGP 8.1.4 + Kotlin 1.9.22 + JDK 17**。
（用更新的 Gradle 9.x 會因 buildDir 棄用等問題失敗，別升。）

Xposed API 來源：JCenter 已於 2024/8 關閉，改用 `maven("https://api.xposed.info/")`
（settings.gradle.kts 已設好）。

## 已知環境細節

- LSPosed 模組標記靠 `AndroidManifest.xml` 的 `xposedmodule` meta-data
- 入口類別由 `assets/xposed_init` 指定
- 作用域清單在 `res/values/arrays.xml`（`xposedscope`），目前只含鳴潮 package
- release 用 debug 簽名（方便 LSPosed 直接裝，不需正式簽章）

---

## 建議的下一步（給 Claude Code 的行動清單）

1. **先跟使用者確認 LSPosed 日誌**（過濾 `wwmd222`），判定是「沒載入 / hook沒觸發 / 有觸發但伺服器不認」哪一種。
2. 若 hook 沒觸發：**反編譯鳴潮 APK（jadx）**，搜 `orderToken`/`storeArea`/`data=`，
   定位真正的序列化與組包路徑。
3. 依定位結果換 hook 點（優先考慮 Base64 編碼點或 OkHttp body 層的通用攔截）。
4. 若涉及 `sign=` 校驗，需逆出 sign 演算法（很可能是 md5(排序後參數 + salt)），
   改 data 後同步重算 sign，否則伺服器會拒。
5. 保留 `OrderTokenPatcher.kt` 的演算法不變（已驗證），只改「在哪裡呼叫它」。
6. 取得一台 TW 真實封包校準 `TW_BLOCK` 與 `REGION_OFFSET`。

## 重要提醒

- 本模組修改遊戲支付請求以改變訂單地區/定價，屬於規避服務商地區定價與風控的行為，
  可能違反遊戲服務條款，並有帳號風險。請與使用者確認其了解風險。
- 不要在 `sign` 未同步重算的情況下修改 `data`，否則不只無效，還可能觸發伺服器風控標記。
