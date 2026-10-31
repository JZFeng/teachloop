// 路由已挂：`/admin/vocab/books/:bookId/import`（App.tsx，守卫 `nav.vocab.books`）。
// 2026-10-30 用户要求「新建时直接贴词条，别分两步」之后，词条导入已经内联进
// `/admin/vocab/books` 的编辑面板（`AdminBooksPage.tsx` 的「词条」区），这一屏不再单独存在。
// 保留这条路由只为不让旧链接/收藏 404：直接跳到那本书的面板，粘贴、预览、导入都在那里。
import { Navigate, useParams } from 'react-router-dom'

export default function BookImportPage() {
  const { bookId = '' } = useParams()
  return <Navigate replace to={`/admin/vocab/books?edit=${encodeURIComponent(bookId)}`} />
}
