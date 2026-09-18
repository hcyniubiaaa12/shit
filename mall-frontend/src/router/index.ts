import { createRouter, createWebHistory } from 'vue-router'

/**
 * 两级路由空间：
 * `/`        商城前台（游客 + 普通用户）
 * `/admin`   管理后台（平台管理员 + 商家，按 JWT type 显示菜单）
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/layouts/MallLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('@/views/mall/Home.vue') },
        { path: 'product/:id', name: 'product-detail', component: () => import('@/views/mall/ProductDetail.vue') },
        { path: 'shop/:merchantId', name: 'shop', component: () => import('@/views/mall/ShopPage.vue') },
        { path: 'cart', name: 'cart', component: () => import('@/views/mall/CartPage.vue') },
        { path: 'orders', name: 'orders', component: () => import('@/views/mall/OrdersPage.vue') },
        { path: 'profile', name: 'profile', component: () => import('@/views/mall/ProfilePage.vue') },
        // 静态路径必须放在 pay/:orderNo 之前，否则 "result" 会被当成订单号匹配
        { path: 'pay/result', name: 'pay-result', component: () => import('@/views/mall/PayResult.vue') },
        { path: 'pay/:orderNo', name: 'pay', component: () => import('@/views/mall/PayPage.vue') },
        { path: 'login', name: 'login', component: () => import('@/views/mall/Login.vue') },
        { path: 'register', name: 'register', component: () => import('@/views/mall/Register.vue') },
      ],
    },
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminLayout.vue'),
      children: [
        { path: '', name: 'admin-home', component: () => import('@/views/admin/AdminHome.vue') },
        { path: 'categories', name: 'admin-categories', component: () => import('@/views/admin/AdminCategories.vue') },
        { path: 'adverts', name: 'admin-adverts', component: () => import('@/views/admin/AdminAdverts.vue') },
        { path: 'products', name: 'merchant-products', component: () => import('@/views/admin/MerchantProducts.vue') },
        { path: 'orders', name: 'admin-orders', component: () => import('@/views/admin/AdminOrders.vue') },
        { path: 'merchants', name: 'admin-merchants', component: () => import('@/views/admin/AdminMerchants.vue') },
        { path: 'withdrawals', name: 'admin-withdrawals', component: () => import('@/views/admin/AdminWithdrawals.vue') },
        { path: 'withdrawal', name: 'merchant-withdrawal', component: () => import('@/views/admin/MerchantWithdrawal.vue') },
        { path: 'shop', name: 'merchant-shop', component: () => import('@/views/admin/MerchantShop.vue') },
        { path: 'refunds', name: 'admin-refunds', component: () => import('@/views/admin/AdminRefunds.vue') },
        { path: 'employees', name: 'admin-employees', component: () => import('@/views/admin/AdminEmployees.vue') },
        { path: 'reports', name: 'admin-reports', component: () => import('@/views/admin/AdminReports.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/** 路由守卫：需要登录的页面（我的订单 / 后台）未登录跳登录页；后台仅 type 1/2 可进 */
router.beforeEach((to) => {
  const token = localStorage.getItem('mall_token')
  const type = Number(localStorage.getItem('mall_user_type') ?? -1)
  const isAdmin = to.path.startsWith('/admin')
  const needAuth = isAdmin || to.name === 'orders' || to.name === 'profile'

  if (needAuth && !token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (isAdmin && type !== 1 && type !== 2) {
    return { name: 'home' }
  }
})

export default router
