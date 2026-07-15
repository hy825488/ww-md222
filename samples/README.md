# 樣本說明

真實封包原始檔在使用者手上（`鳴潮正常封包.txt`），因含 access_token / adid 等
敏感欄位，未放入 repo。Claude Code 需要時請向使用者索取。

## 已提取的關鍵分析結果（MYR 馬來西亞區封包）

- POST body 格式: `sign=<md5>&data=<base64(JSON)>&pcode=G153&timestamp=<ts>`
- data 解 base64 後是 JSON 明文，含欄位: storeCurrency, orderToken, payId, pk, ...
- storeCurrency = "MYR"
- **注意封包裡沒有 storeArea 欄位**（只有 storeCurrency），iOS 版清 storeArea 的邏輯
  在此封包上可能無對應欄位，需確認當前版本是否還有 storeArea。

### orderToken 逐層拆解
```
orderToken (716 base64 chars)
  → base64 decode → 537 bytes (header 78da)
  → zlib inflate → 960 char hex string
  → offset 416, len 64 = 地區 block
      MYR 值: 4d251b431d5a9acfcd25f20c71f91333c0796dee90056082486c19577a0d7f20
```

### 需要向使用者索取
1. 一台**台灣區(TW)**的真實封包 → 解出其 offset 416，確認 TW_BLOCK 是否 =
   `04fcba8ebfccc7ee1a2df7d3561ae27e2867dd17d120e5c5cf407d89ee3aae57`
2. 若能提供**同帳號 TW + 非TW 兩筆**，diff 兩個 960-hex 字串即可精確定位
   真正變動的地區欄位位置（驗證 offset 416 是否正確）。
