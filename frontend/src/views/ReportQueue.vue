<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-800 mb-2">上送队列</h1>
          <p class="text-gray-500">数据按批次异步上送国家平台，失败可按配置继续或暂停，每批请求/响应均可追踪</p>
        </div>
        <el-button @click="fetchJobs" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 汇总统计 -->
    <div class="grid grid-cols-2 md:grid-cols-5 gap-4">
      <div class="card text-center">
        <p class="text-2xl font-bold text-gray-500">{{ summary.pending }}</p>
        <p class="text-gray-500 text-sm mt-1">待上送（条）</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-blue-600">{{ summary.sending }}</p>
        <p class="text-gray-500 text-sm mt-1">上送中（条）</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-green-600">{{ summary.success }}</p>
        <p class="text-gray-500 text-sm mt-1">成功（条）</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-red-600">{{ summary.fail }}</p>
        <p class="text-gray-500 text-sm mt-1">失败（条）</p>
      </div>
      <div class="card text-center">
        <p class="text-2xl font-bold text-purple-600">{{ runningJobs }}</p>
        <p class="text-gray-500 text-sm mt-1">进行中任务</p>
      </div>
    </div>

    <!-- 任务列表 -->
    <div class="card">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-700">上送任务</h2>
        <el-select v-model="statusFilter" placeholder="任务状态" clearable style="width: 160px"
                   @change="handleFilterChange">
          <el-option label="待运行" value="PENDING" />
          <el-option label="上送中" value="SENDING" />
          <el-option label="已暂停" value="PAUSED" />
          <el-option label="全部成功" value="SUCCESS" />
          <el-option label="部分成功" value="PARTIAL_SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="jobs" stripe style="width: 100%">
        <el-table-column prop="jobNo" label="任务编号" width="210">
          <template #default="{ row }">
            <span class="font-mono text-xs">{{ row.jobNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件 / 导入批次" min-width="200">
          <template #default="{ row }">
            <div class="font-medium text-gray-700">{{ row.fileName || '-' }}</div>
            <div class="text-xs text-gray-400 font-mono">{{ row.batchNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="230">
          <template #default="{ row }">
            <el-progress
              :percentage="jobPercent(row)"
              :status="progressStatus(row.status)"
              :stroke-width="14"
              :text-inside="true"
            />
            <div class="text-xs text-gray-400 mt-1">
              共{{ row.totalBatches }}批 / 每批{{ row.batchSize }}条
            </div>
          </template>
        </el-table-column>
        <el-table-column label="待上送" width="75" align="center">
          <template #default="{ row }">
            <span class="text-gray-500 font-medium">{{ row.pendingCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上送中" width="75" align="center">
          <template #default="{ row }">
            <span class="text-blue-600 font-medium">{{ row.sendingCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成功" width="75" align="center">
          <template #default="{ row }">
            <span class="text-green-600 font-medium">{{ row.successCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败" width="75" align="center">
          <template #default="{ row }">
            <span class="text-red-600 font-medium">{{ row.failCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败策略" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.failStrategy === 'CONTINUE' ? 'warning' : 'info'">
              {{ row.failStrategy === 'CONTINUE' ? '继续' : '暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="jobStatusType(row.status)">{{ jobStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewBatches(row)">批次追踪</el-button>
            <el-button v-if="row.status === 'SENDING' || row.status === 'PENDING'"
                       link type="warning" size="small" @click="pauseJob(row)">暂停</el-button>
            <el-button v-if="row.status === 'PAUSED'"
                       link type="success" size="small" @click="resumeJob(row)">继续</el-button>
            <el-button v-if="row.status === 'PAUSED' || row.status === 'PARTIAL_SUCCESS' || row.status === 'FAILED'"
                       link type="primary" size="small" @click="retryJob(row)">重试</el-button>
            <el-button v-if="row.status !== 'SUCCESS' && row.status !== 'CANCELLED'"
                       link type="danger" size="small" @click="cancelJob(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchJobs"
          @current-change="fetchJobs"
        />
      </div>
    </div>

    <!-- 批次追踪弹窗 -->
    <el-dialog v-model="batchDialogVisible" :title="`批次追踪 - ${currentJob?.jobNo || ''}`"
               width="900px" top="6vh">
      <div v-if="currentJob" class="mb-4 p-3 bg-gray-50 rounded-lg text-sm text-gray-600">
        {{ currentJob.message }}
      </div>

      <el-table :data="batches" stripe max-height="420" size="small">
        <el-table-column prop="seqNo" label="批次" width="60" align="center" />
        <el-table-column label="条数" width="60" align="center">
          <template #default="{ row }">{{ row.totalCount }}</template>
        </el-table-column>
        <el-table-column label="成功" width="60" align="center">
          <template #default="{ row }">
            <span class="text-green-600">{{ row.successCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败" width="60" align="center">
          <template #default="{ row }">
            <span class="text-red-600">{{ row.failCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="batchStatusType(row.status)">
              {{ batchStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="HTTP" width="70" align="center">
          <template #default="{ row }">
            <span :class="httpClass(row.httpStatus)">{{ row.httpStatus || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">
            {{ row.durationMs != null ? row.durationMs + ' ms' : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="sendTime" label="上送时间" width="170">
          <template #default="{ row }">{{ formatTime(row.sendTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="!row.requestBody"
                       @click="viewPayload(row, 'request')">请求</el-button>
            <el-button link type="primary" size="small" :disabled="!row.responseBody && !row.errorMessage"
                       @click="viewPayload(row, 'response')">响应</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="batchDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 报文查看弹窗 -->
    <el-dialog v-model="payloadDialogVisible" :title="payloadTitle" width="760px" top="8vh" append-to-body>
      <div v-if="payloadBatch" class="space-y-3">
        <div class="text-sm text-gray-500">
          <span class="mr-4">批次 #{{ payloadBatch.seqNo }}</span>
          <span class="mr-4">HTTP: {{ payloadBatch.httpStatus || '-' }}</span>
          <span>耗时: {{ payloadBatch.durationMs != null ? payloadBatch.durationMs + ' ms' : '-' }}</span>
        </div>
        <div v-if="payloadType === 'request'">
          <div class="text-xs text-gray-400 mb-1 break-all">{{ payloadBatch.requestUrl }}</div>
          <pre class="bg-gray-900 text-green-300 rounded-lg p-4 text-xs overflow-auto" style="max-height: 420px">{{ pretty(payloadBatch.requestBody) }}</pre>
        </div>
        <div v-else>
          <div v-if="payloadBatch.errorMessage" class="text-red-600 text-sm mb-2">
            {{ payloadBatch.errorMessage }}
          </div>
          <pre class="bg-gray-900 text-green-300 rounded-lg p-4 text-xs overflow-auto" style="max-height: 420px">{{ pretty(payloadBatch.responseBody) }}</pre>
        </div>
      </div>
      <template #footer>
        <el-button @click="payloadDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '@/api'

const loading = ref(false)
const jobs = ref([])
const statusFilter = ref('')
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

const batchDialogVisible = ref(false)
const payloadDialogVisible = ref(false)
const currentJob = ref(null)
const batches = ref([])
const payloadBatch = ref(null)
const payloadType = ref('request')

let timer = null

const summary = reactive({ pending: 0, sending: 0, success: 0, fail: 0 })

const runningJobs = computed(() =>
  jobs.value.filter(j => j.status === 'SENDING' || j.status === 'PENDING').length
)

const jobPercent = (row) => {
  if (!row.totalCount) return 0
  const done = (row.successCount || 0) + (row.failCount || 0)
  return Math.min(100, Math.round((done * 100) / row.totalCount))
}

const progressStatus = (status) => {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'exception'
  if (status === 'PARTIAL_SUCCESS') return 'warning'
  return undefined
}

const jobStatusType = (s) => ({
  PENDING: 'info', SENDING: '', PAUSED: 'warning', SUCCESS: 'success',
  PARTIAL_SUCCESS: 'warning', FAILED: 'danger', CANCELLED: 'info'
}[s] || 'info')

const jobStatusText = (s) => ({
  PENDING: '待运行', SENDING: '上送中', PAUSED: '已暂停', SUCCESS: '全部成功',
  PARTIAL_SUCCESS: '部分成功', FAILED: '失败', CANCELLED: '已取消'
}[s] || s)

const batchStatusType = (s) => ({
  PENDING: 'info', SENDING: '', SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info'
}[s] || 'info')

const batchStatusText = (s) => ({
  PENDING: '待上送', SENDING: '上送中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消'
}[s] || s)

const httpClass = (code) => {
  if (code == null) return 'text-gray-400'
  return code >= 200 && code < 300 ? 'text-green-600 font-medium' : 'text-red-600 font-medium'
}

const formatTime = (t) => (t ? new Date(t).toLocaleString('zh-CN') : '-')

const pretty = (text) => {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

const payloadTitle = computed(() =>
  payloadType.value === 'request' ? '请求报文（上送清单）' : '响应报文（国家平台回执）'
)

const fetchJobs = async () => {
  loading.value = true
  try {
    const res = await reportApi.getJobs({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      status: statusFilter.value || undefined
    })
    jobs.value = res.data.records || []
    pagination.total = res.data.total || 0

    // 汇总当前页数据（列表已展示各任务四项数量）
    summary.pending = jobs.value.reduce((s, j) => s + (j.pendingCount || 0), 0)
    summary.sending = jobs.value.reduce((s, j) => s + (j.sendingCount || 0), 0)
    summary.success = jobs.value.reduce((s, j) => s + (j.successCount || 0), 0)
    summary.fail = jobs.value.reduce((s, j) => s + (j.failCount || 0), 0)

    // 批次追踪弹窗打开时，同步刷新批次与任务
    if (batchDialogVisible.value && currentJob.value) {
      const updated = jobs.value.find(j => j.id === currentJob.value.id)
      if (updated) currentJob.value = updated
      await fetchBatches(currentJob.value.id, true)
    }
  } catch (e) {
    // 拦截器统一提示
  } finally {
    loading.value = false
  }
}

const handleFilterChange = () => {
  pagination.pageNum = 1
  fetchJobs()
}

const viewBatches = async (row) => {
  currentJob.value = row
  batchDialogVisible.value = true
  await fetchBatches(row.id)
}

const fetchBatches = async (jobId, silent = false) => {
  try {
    const res = await reportApi.getBatches(jobId)
    batches.value = res.data || []
  } catch (e) {
    if (!silent) ElMessage.error('获取批次失败')
  }
}

const viewPayload = (batch, type) => {
  payloadBatch.value = batch
  payloadType.value = type
  payloadDialogVisible.value = true
}

const pauseJob = async (row) => {
  try {
    await ElMessageBox.confirm('暂停后当前批次执行完即停止，确定暂停该任务？', '确认暂停', { type: 'warning' })
    await reportApi.pause(row.id)
    ElMessage.success('任务将在当前批次完成后暂停')
    fetchJobs()
  } catch (e) { /* cancel */ }
}

const resumeJob = async (row) => {
  try {
    await reportApi.resume(row.id)
    ElMessage.success('任务继续上送')
    fetchJobs()
  } catch (e) { /* handled */ }
}

const retryJob = async (row) => {
  try {
    await ElMessageBox.confirm('将重置本批次失败数据并重新上送失败批次，确定重试？', '确认重试', { type: 'warning' })
    await reportApi.retry(row.id)
    ElMessage.success('开始重试失败批次')
    fetchJobs()
  } catch (e) { /* cancel */ }
}

const cancelJob = async (row) => {
  try {
    await ElMessageBox.confirm('取消后未上送批次不再发送，已上送数据不回滚，确定取消？', '确认取消', {
      type: 'warning',
      confirmButtonText: '确定取消'
    })
    await reportApi.cancel(row.id)
    ElMessage.success('任务已取消')
    fetchJobs()
  } catch (e) { /* cancel */ }
}

onMounted(() => {
  fetchJobs()
  // 每2秒轮询，保证上送中/成功/失败数量实时更新
  timer = setInterval(fetchJobs, 2000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>
