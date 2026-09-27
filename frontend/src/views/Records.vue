<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <h1 class="text-2xl font-bold text-gray-800 mb-2">导入记录</h1>
      <p class="text-gray-500">查看历史导入记录，管理数据上报</p>
    </div>

    <!-- 记录列表 -->
    <div class="card">
      <el-table
        v-loading="loading"
        :data="records"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="batchNo" label="批次号" width="220">
          <template #default="{ row }">
            <span class="font-mono text-sm">{{ row.batchNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件名" min-width="180" />
        <el-table-column prop="totalCount" label="总记录数" width="100" align="center">
          <template #default="{ row }">
            <span class="font-medium">{{ row.totalCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="successCount" label="成功数" width="100" align="center">
          <template #default="{ row }">
            <span class="text-green-600 font-medium">{{ row.successCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="failCount" label="失败数" width="100" align="center">
          <template #default="{ row }">
            <span class="text-red-600 font-medium">{{ row.failCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="100" />
        <el-table-column prop="createTime" label="导入时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="flex space-x-2">
              <el-button type="primary" link size="small" @click="viewDetail(row)">
                详情
              </el-button>
              <el-button
                v-if="row.status === 1"
                type="success"
                link
                size="small"
                @click="openQueueDialog(row)"
              >
                上送
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </div>

    <!-- 加入上送队列配置弹窗 -->
    <el-dialog
      v-model="queueDialogVisible"
      title="加入上送队列"
      width="480px"
      :close-on-click-modal="false"
    >
      <div class="space-y-5 py-2">
        <p class="text-sm text-gray-500">
          批次：<span class="font-mono">{{ currentRow?.batchNo }}</span>
        </p>
        <div class="flex items-center justify-between">
          <span class="text-sm text-gray-700">每批上送数量</span>
          <el-input-number v-model="queueForm.batchSize" :min="1" :max="5000" :step="100" />
        </div>
        <div>
          <p class="text-sm text-gray-700 mb-2">某批失败时</p>
          <el-radio-group v-model="queueForm.failStrategy">
            <el-radio label="PAUSE">暂停后续批次（推荐）</el-radio>
            <el-radio label="CONTINUE">继续后续批次</el-radio>
          </el-radio-group>
        </div>
        <p class="text-xs text-gray-400">
          加入后后台异步分批上送，不阻塞页面；可在"上送队列"查看进度及每批请求/响应追踪
        </p>
      </div>

      <template #footer>
        <div class="flex justify-end space-x-3">
          <el-button @click="queueDialogVisible = false">取消</el-button>
          <el-button type="success" :loading="queuing" @click="confirmJoinQueue">加入上送队列</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { excelApi, reportApi } from '@/api'

const router = useRouter()

const loading = ref(false)
const records = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const queueDialogVisible = ref(false)
const queuing = ref(false)
const currentRow = ref(null)
const queueForm = reactive({
  batchSize: 500,
  failStrategy: 'PAUSE'
})

const getStatusType = (status) => {
  const types = { 0: 'info', 1: 'success', 2: 'warning' }
  return types[status] || 'info'
}

const getStatusText = (status) => {
  const texts = { 0: '处理中', 1: '完成', 2: '部分失败' }
  return texts[status] || '未知'
}

const formatTime = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

const fetchRecords = async () => {
  loading.value = true
  try {
    const res = await excelApi.getRecords({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    })
    records.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch (error) {
    // 错误已在拦截器中处理
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (size) => {
  pagination.pageSize = size
  fetchRecords()
}

const handleCurrentChange = (page) => {
  pagination.pageNum = page
  fetchRecords()
}

const viewDetail = (row) => {
  router.push(`/data/${row.batchNo}`)
}

const openQueueDialog = (row) => {
  currentRow.value = row
  queueForm.batchSize = 500
  queueForm.failStrategy = 'PAUSE'
  queueDialogVisible.value = true
}

const confirmJoinQueue = async () => {
  if (!currentRow.value) return
  queuing.value = true
  try {
    await reportApi.createJob(currentRow.value.batchNo, {
      batchSize: queueForm.batchSize,
      failStrategy: queueForm.failStrategy
    })
    ElMessage.success('已加入上送队列，后台正在分批上送')
    queueDialogVisible.value = false
    router.push('/report-queue')
  } catch (error) {
    // 拦截器统一提示
  } finally {
    queuing.value = false
  }
}

onMounted(() => {
  fetchRecords()
})
</script>
