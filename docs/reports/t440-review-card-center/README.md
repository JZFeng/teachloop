# T440 截图证据：卡片正面居中，反面与查词页不动

| 文件 | 拍的是什么 | 结论 |
| :-- | :-- | :-- |
| `review-front.png` | `today-review`（`/vocab/review`）的 `review-front` | 单词居中（中心 666 = 卡片中心 666）、英/美音标另起一行居中、图标在右上角 |
| `review-back-reverted.png` | 同一张卡翻面后的 `review-back`（T440 补正后） | 单词与音标**同一行左对齐**（单词左距 41px）、图标 `position: static` 右对齐 —— 回到 T437 版式 |
| `word-detail-unchanged.png` | `word-details`（`/vocab/words/by-form/brave`） | 卡片 class 只有 `review-card is-flipped`（无 `is-centered`），一行左对齐版式未变 |
| `free-review.png` | `free-review`（`/vocab/review/session/:id`）的正面 | 与 `review-front` 同一套居中版式（同一组件 + `centered`） |

补正时间：2026-10-25。上一版的 `review-back.png`（反面单词被居中）已删除——那是超范围改动的状态，不再是当前实现。
