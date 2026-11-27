<script setup lang="ts">
import { RestaurantCatalog } from '@/modules/restaurant'
// 页面负责组合找店与写评入口，不把点评列表扩展为独立首页内容流。
</script>
<template>
  <div class="discovery-page">
    <h1 class="screen-reader-title">泥电美食地图</h1>
    <RestaurantCatalog />
    <footer class="discovery-footer">
      <span>泥电美食地图 · 校园美食评价社区</span>
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">
        蜀ICP备2026074867号-1
      </a>
    </footer>
  </div>
</template>
<style scoped src="./home.css"></style>
