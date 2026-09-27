<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <div class="flex items-center justify-between">
        <div>
          <div class="flex items-center mb-2">
            <el-button link @click="goBack" class="mr-2">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
              </svg>
            </el-button>
            <h1 class="text-2xl font-bold text-gray-800">数据详情</h1>
          </div>
          <p class="text-gray-500">批次号：{{ batchNo }}</p>
        </div>
        <el-button type="primary" @click="openEnqueue" :disabled="!stats.pending && !stats.failed">
          <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
          </svg>
          加入上送队列
        </el-button>
        <el-button @click="goQueue">查看队列</el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="grid grid-cols-5 gap-4">
      <div class="card flex items-center">
        <div class="w-12 h-12 bg-blue-100 rounded-xl flex items-center justify-center mr-4">
          <svg class="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-800">{{ stats.total }}</p>
          <p class="text-gray-500 text-sm">总记录数</p>
        </div>
      </div>
      <div class="card flex items-center">
        <div class="w-12 h-12 bg-yellow-100 rounded-xl flex items-center justify-center mr-4">
          <svg class="w-6 h-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-800">{{ stats.pending }}</p>
          <p class="text-gray-500 text-sm">待上送</p>
        </div>
      </div>
      <div class="card flex items-center">
        <div class="w-12 h-12 bg-blue-100 rounded-xl flex items-center justify-center mr-4">
          <svg class="w-6 h-6 text-blue-600 animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
        </div>
        <div>
          <p :class="['text-2xl font-bold', stats.sending > 0 ? 'text-blue-600' : 'text-gray-800']">{{ stats.sending }}</p>
          <p class="text-gray-500 text-sm">上送中</p>
        </div>
      </div>
      <div class="card flex items-center">
        <div class="w-12 h-12 bg-green-100 rounded-xl flex items-center justify-center mr-4">
          <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-800">{{ stats.success }}</p>
          <p class="text-gray-500 text-sm">已上报</p>
        </div>
      </div>
      <div class="card flex items-center">
        <div class="w-12 h-12 bg-red-100 rounded-xl flex items-center justify-center mr-4">
          <svg class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </div>
        <div>
          <p class="text-2xl font-bold text-gray-800">{{ stats.failed }}</p>
          <p class="text-gray-500 text-sm">上报失败</p>
        </div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-700">数据列表</h2>
        <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width: 140px" @change="fetchData">
          <el-option label="全部" value="" />
          <el-option label="待上送" :value="0" />
          <el-option label="已上送" :value="1" />
          <el-option label="上报失败" :value="2" />
          <el-option label="上送中" :value="3" />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="dataList" stripe style="width: 100%">
        <el-table-column prop="dataCode" label="数据编号" width="140" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="idCard" label="身份证号" width="180">
          <template #default="{ row }">
            {{ maskIdCard(row.idCard) }}
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="130">
          <template #default="{ row }">
            {{ maskPhone(row.phone) }}
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="120" align="right">
          <template #default="{ row }">
            <span class="font-medium">{{ formatAmount(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="reportStatus" label="上报状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getReportStatusType(row.reportStatus)" size="small">
              {{ getReportStatusText(row.reportStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reportMessage" label="上报信息" min-width="180" show-overflow-tooltip />
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
      v-model="enqueueVisible"
      title="加入上送队列"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px">
        <el-form-item label="批次号">
          <span class="font-mono text-sm">{{ batchNo }}</span>
        </el-form-item>
        <el-form-item label="待上送">
          <span class="text-blue-600 font-medium">{{ stats.pending }} 条</span>
        </el-form-item>
        <el-form-item label="每批条数">
          <el-input-number v-model="enqueueForm.batchSize" :min="1" :max="5000" :step="50" />
        </el-form-item>
        <el-form-item label="某批失败时">
          <el-radio-group v-model="enqueueForm.continueOnFail">
            <el-radio :value="false">暂停后续批次</el-radio>
            <el-radio :value="true">继续后续批次</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="入队后异步上送，页面不阻塞；可在“上送队列”查看待上送/上送中/成功/失败实时进度，并逐批追踪报文。"
      />
      <template #footer>
        <el-button @click="enqueueVisible = false">取消</el-button>
        <el-button type="primary" :loading="enqueuing" @click="confirmEnqueue">确认入队</el-button>
      </template>
    </el-dialog>

    <!-- 进行中任务进度提示 -->
    <el-dialog v-model="progressVisible" title="上送进度" width="560px" :close-on-click-modal="false">
      <div class="grid grid-cols-4 gap-3 mb-4">
        <div class="bg-gray-50 rounded-lg p-3 text-center">
          <p class="text-xl font-bold text-gray-600">{{ stats.pending }}</p><p class="text-xs text-gray-500">待上送</p>
        </div>
        <div class="bg-blue-50 rounded-lg p-3 text-center">
          <p class="text-xl font-bold text-blue-600">{{ stats.sending }}</p><p class="text-xs text-gray-500">上送中</p>
        </div>
        <div class="bg-green-50 rounded-lg p-3 text-center">
          <p class="text-xl font-bold text-green-600">{{ stats.success }}</p><p class="text-xs text-gray-500">成功</p>
        </div>
        <div class="bg-red-50 rounded-lg p-3 text-center">
          <p class="text-xl font-bold text-red-600">{{ stats.failed }}</p><p class="text-xs text-gray-500">失败</p>
        </div>
      </div>
      <el-alert
        v-if="currentTask?.status === 'PAUSED'"
        type="warning" :closable="false"
        :title="currentTask.errorMessage || '任务已暂停，可在队列页继续或重试'" />
      <template #footer>
        <el-button @click="progressVisible = false">关闭</el-button>
        <el-button type="primary" @click="goQueue">前往上送队列</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { excelApi, reportTaskApi } from '@/api'

const route = useRoute()
const router = useRouter()

const batchNo = computed(() => route.params.batchNo)
const loading = ref(false)
const dataList = ref([])
const statusFilter = ref('')

const enqueueVisible = ref(false)
const enqueuing = ref(false)
const progressVisible = ref(false)
const currentTask = ref(null)
const enqueueForm = reactive({ batchSize: 100, continueOnFail: false })

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const stats = reactive({
  total: 0,
  pending: 0,
  sending: 0,
  success: 0,
  failed: 0
})

let statsTimer = null

const getReportStatusType = (status) => {
  const types = { 0: 'info', 1: 'success', 2: 'danger', 3: 'primary' }
  return types[status] ?? 'info'
}

const getReportStatusText = (status) => {
  const texts = { 0: '待上送', 1: '上送成功', 2: '上送失败', 3: '上送中' }
  return texts[status] ?? '未知'
}

const maskIdCard = (idCard) => {
  if (!idCard) return '-'
  return idCard.replace(/^(.{6})(.*)(.{4})$/, '$1********$3')
}

const maskPhone = (phone) => {
  if (!phone) return '-'
  return phone.replace(/^(.{3})(.*)(.{4})$/, '$1****$3')
}

const formatAmount = (amount) => {
  if (amount === null || amount === undefined) return '-'
  return '¥' + Number(amount).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

const fetchStatusStats = async () => {
  try {
    const res = await reportTaskApi.statusCount(batchNo.value)
    Object.assign(stats, res.data)
  } catch { /* ignore */ }
}

const fetchCurrentTask = async () => {
  try {
    const res = await reportTaskApi.byBatchNo(batchNo.value)
    currentTask.value = res.data
  } catch {
    currentTask.value = null
  }
}

const fetchData = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    }
    if (statusFilter.value !== '') {
      params.reportStatus = statusFilter.value
    }

    const res = await excelApi.getDataByBatch(batchNo.value, params)
    dataList.value = res.data.records || []
    pagination.total = res.data.total || 0
    fetchStatusStats()
  } catch (error) {
    // 错误已在拦截器中处理
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (size) => {
  pagination.pageSize = size
  fetchData()
}

const handleCurrentChange = (page) => {
  pagination.pageNum = page
  fetchData()
}

const goBack = () => {
  router.push('/records')
}

const goQueue = () => {
  router.push('/report-tasks')
}

const openEnqueue = async () => {
  await fetchCurrentTask()
  if (currentTask.value && ['PENDING', 'RUNNING', 'PAUSED'].includes(currentTask.value.status)) {
    ElMessage.info('该批次已有上送任务')
    progressVisible.value = true
    return
  }
  if (stats.pending <= 0) {
    ElMessage.warning('没有待上送的数据')
    return
  }
  enqueueForm.batchSize = 100
  enqueueForm.continueOnFail = false
  enqueueVisible.value = true
}

const confirmEnqueue = async () => {
  enqueuing.value = true
  try {
    await reportTaskApi.create({
      batchNo: batchNo.value,
      batchSize: enqueueForm.batchSize,
      continueOnFail: enqueueForm.continueOnFail
    })
    ElMessage.success('已加入上送队列')
    enqueueVisible.value = false
    await fetchCurrentTask()
    progressVisible.value = true
    fetchStatusStats()
  } catch {
    // 拦截器已提示
  } finally {
    enqueuing.value = false
  }
}

onMounted(async () => {
  await fetchData()
  await fetchCurrentTask()
  // 从导入页"加入上送队列"跳转过来时自动打开配置弹窗
  if (route.query.enqueue === '1' && stats.pending > 0
      && (!currentTask.value || !['PENDING', 'RUNNING', 'PAUSED'].includes(currentTask.value.status))) {
    enqueueVisible.value = true
  }
  // 有上送中/排队任务时轮询状态
  statsTimer = setInterval(async () => {
    await fetchStatusStats()
    if (currentTask.value && ['PENDING', 'RUNNING', 'PAUSED'].includes(currentTask.value.status)) {
      await fetchCurrentTask()
    }
  }, 2000)
})

onUnmounted(() => {
  if (statsTimer) clearInterval(statsTimer)
})
</script>
