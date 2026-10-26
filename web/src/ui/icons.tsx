/**
 * 图标唯一出口：页面只能从这里拿图标，不允许直接 `import '@ant-design/icons'`。
 * 这条约束由 `scripts/check-ui.py` 的第 3 条规则守着；新增图标先登记到这份清单。
 *
 * 只放线性（Outlined）图标：统一 1em 字号、继承 currentColor，颜色由使用处的 CSS 决定。
 * 例外：StarFilled 只表达「已收藏」这个开关的选中态，不承担正文。
 */
export {
  AppstoreOutlined,
  ArrowLeftOutlined,
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  BookOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  CodeOutlined,
  CommentOutlined,
  CompressOutlined,
  DeleteOutlined,
  EditOutlined,
  ExpandAltOutlined,
  FileTextOutlined,
  FlagOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  HighlightOutlined,
  HomeOutlined,
  InfoCircleOutlined,
  LogoutOutlined,
  MoonOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
  StarFilled,
  StarOutlined,
  SoundOutlined,
  SunOutlined,
  SyncOutlined,
  TeamOutlined,
  UploadOutlined,
  UserOutlined,
} from '@ant-design/icons'
