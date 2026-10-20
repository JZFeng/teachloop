import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { teacherZh, teacherEn } from './teacher/locales'
import { examZh, examEn } from './exam/locales'
import { studentZh, studentEn } from './student/locales'

const baseZh = {
  app: { name: 'AI 批改平台', tagline: '让批改回到教学' },
  nav: {
    home: '首页', classes: '班级', papers: '试卷', grading: '批改', review: '复核',
    reports: '报表', settings: '设置', exams: '考试', assignments: '作业', feedback: '我的反馈',
    notifications: '通知',
    profile: '资料',
    aiSettings: 'AI 设置',
  },
  common: {
    save: '保存', cancel: '取消', confirm: '确定', back: '返回', next: '下一步', prev: '上一步',
    loading: '加载中…', empty: '暂无数据', error: '出错了', retry: '重试', logout: '退出登录',
    search: '搜索', all: '全部', page: '第 {{page}} 页', total: '共 {{total}} 条', exampleData: '示例数据',
  },
  notifications: {
    title: '通知', sub: '发布、成绩与复核结果都会在这里出现',
    unreadOnly: '只看未读', markRead: '标为已读', markAll: '全部已读', empty: '没有通知',
    unreadCount: '{{n}} 条未读', read: '已读',
  },
  login: {
    title: '登录', subtitle: '开发期用邮箱密码登录；微信扫码在接入后启用',
    email: '邮箱', password: '密码', submit: '登录', hint: '开发账号：teacher@teachloop.local / student@teachloop.local，密码 teachloop123',
  },
}

const baseEn = {
  app: { name: 'TeachLoop', tagline: 'Grading that serves teaching' },
  nav: {
    home: 'Home', classes: 'Classes', papers: 'Papers', grading: 'Grading', review: 'Review',
    reports: 'Reports', settings: 'Settings', exams: 'Exams', assignments: 'Assignments', feedback: 'My feedback',
    notifications: 'Notifications',
    profile: 'Profile',
    aiSettings: 'AI settings',
  },
  common: {
    save: 'Save', cancel: 'Cancel', confirm: 'Confirm', back: 'Back', next: 'Next', prev: 'Previous',
    loading: 'Loading…', empty: 'Nothing here yet', error: 'Something went wrong', retry: 'Retry', logout: 'Sign out',
    search: 'Search', all: 'All', page: 'Page {{page}}', total: '{{total}} in total', exampleData: 'Sample data',
  },
  notifications: {
    title: 'Notifications', sub: 'Publishing, scores and review results appear here',
    unreadOnly: 'Unread only', markRead: 'Mark read', markAll: 'Mark all read', empty: 'No notifications',
    unreadCount: '{{n}} unread', read: 'Read',
  },
  login: {
    title: 'Sign in', subtitle: 'Email and password for now; WeChat sign-in comes later',
    email: 'Email', password: 'Password', submit: 'Sign in', hint: 'Dev accounts: teacher@teachloop.local / student@teachloop.local, password teachloop123',
  },
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      'zh-CN': { translation: { ...baseZh, teacher: teacherZh, exam: examZh, student: studentZh } },
      en: { translation: { ...baseEn, teacher: teacherEn, exam: examEn, student: studentEn } },
    },
    fallbackLng: 'zh-CN',
    supportedLngs: ['zh-CN', 'en'],
    nonExplicitSupportedLngs: false,
    load: 'currentOnly',
    detection: { order: ['localStorage', 'navigator'], lookupLocalStorage: 'teachloop.lang', caches: ['localStorage'] },
    interpolation: { escapeValue: false },
  })

export default i18n
