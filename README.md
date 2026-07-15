# WW md222 Patch - LSPosed 模組（安卓版）

鳴潮國際版（Wuthering Waves Global）支付請求 patch，改寫自 iOS 版 md222 tweak。

## 功能

Hook `org.json.JSONObject.put()`（對應 iOS 的 `NSMutableDictionary setObject:forKey:`），
在支付請求序列化前修改：

- **storeArea** → 清空
- **orderToken** → 解出內部 960 hex，把固定位置(offset 416)的地區 block **無條件改成 TW**，再壓回

## 行為：任意來源區 → TW

與 iOS 原版不同：iOS 是「只認 BRL(巴西)才換」。本版利用 iOS 註解指出的
「region block 固定在 hex offset 416」，改成**無條件覆蓋固定位置**，因此
不論來源是巴西、馬來西亞或任何其他區，一律改成台灣(TW)。

## 目標 App

`com.kurogame.wutheringwaves.global`

## 改目標區

編輯 `OrderTokenPatcher.kt` 的 `TW_BLOCK` 常數（64 hex 字元）。

## 驗證狀態

已用**真實鳴潮 MYR(馬來西亞)封包**完整驗證：
- orderToken 格式 = base64(zlib(960 hex 文字))，zlib header 78da
- 編碼格式經比對確認 App 壓縮的是 hex 文字本身（壓出 537 bytes 與原始完全吻合）
- patch 後只改地區欄位、其餘 bytes 完整保留、格式可正確還原
- Kotlin 編譯零錯誤

## 編譯 / 安裝

推 GitHub → Actions 編譯 → 下載 `ww-md222-apk`。
LSPosed 安裝 → 啟用 → 作用域勾鳴潮 → 強停重開。
日誌搜 `wwmd222`，看到 `orderToken region @416: ... -> TW` 即成功。

## 重要提醒

offset 416 是照 iOS 註解走、並用 MYR 封包驗證格式無誤。但「改成 TW 後
伺服器是否接受、定價是否真的變動」屬於伺服器端行為，需實際下單才能確認。
若無效，可能是該區的 region block 不在 416，需另抓樣本校準。
