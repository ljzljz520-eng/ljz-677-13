<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <h1 class="text-2xl font-bold text-gray-800 mb-2">上送队列</h1>
      <p class="text-gray-500">
        导入成功后数据按批次异步上送国家平台，页面不阻塞；可配置每批失败后“继续”或“暂停”，并逐批追踪请求与响应
      </p>
    </div>

    <!-- 任务列表 -->
    <div class="card">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-700">上送任务</h2>
        <el-button :loading="loading" @click="fetchTasks">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="tasks" stripe style="width: 100%">
        <el-table-column label="任务信息" min-width="240">
          <template #default="{ row }">
            <div class="font-mono text-sm text-gray-800">{{ row.taskNo }}</div>
            <div class="text-xs text-gray-400 mt-0.5">
              导入批次：<span class="font-mono">{{ row.batchNo }}</span>
            </div>
            <div v-if="row.fileName" class="text-xs text-gray-400">{{ row.fileName }}</div>
          </template>
        </el-table-column>

        <!-- 四个状态数量 -->
        <el-table-column label="待上送" width="90" align="center">
          <template #default="{ row }">
            <span class="text-gray-500 font-medium">{{ pendingOf(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上送中" width="90" align="center">
          <template #default="{ row }">
            <span :class="sendingOf(row) > 0 ? 'text-blue-600 font-semibold' : 'text-gray-400'">
              {{ sendingOf(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="成功" width="90" align="center">
          <template #default="{ row }">
            <span class="text-green-600 font-medium">{{ row.successCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败" width="90" align="center">
          <template #default="{ row }">
            <span :class="(row.failCount || 0) > 0 ? 'text-red-600 font-semibold' : 'text-gray-400'">
              {{ row.failCount || 0 }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="批次进度" width="150" align="center">
          <template #default="{ row }">
            <el-progress
              :percentage="batchPercentage(row)"
              :stroke-width="14"
              :status="progressStatus(row)"
            />
            <div class="text-xs text-gray-400 mt-1">
              {{ doneBatches(row) }} / {{ row.totalBatches }} 批 · 每批{{ row.batchSize }}条
            </div>
          </template>
        </el-table-column>

        <el-table-column label="失败策略" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.continueOnFail === 1 ? 'warning' : 'info'" size="small">
              {{ row.continueOnFail === 1 ? '失败继续' : '失败暂停' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="taskStatusType(row.status)" size="small">
              {{ taskStatusText(row.status) }}
            </el-tag>
            <div v-if="row.status === 'RUNNING' || row.status === 'PENDING'"
                 class="text-xs text-blue-500 mt-1 animate-pulse">
              队列处理中
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewBatches(row)">批次追踪</el-button>
            <el-button
              v-if="row.status === 'RUNNING' || row.status === 'PENDING'"
              type="warning" link size="small" @click="pause(row)">暂停</el-button>
            <el-button
              v-if="row.status === 'PAUSED'"
              type="success" link size="small" @click="resume(row)">继续</el-button>
            <el-button
              v-if="canRetry(row)"
              type="danger" link size="small" @click="retryFailed(row)">重试失败</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 暂停原因提示 -->
      <div v-if="pausedReason" class="mt-3 p-3 bg-yellow-50 rounded-lg text-sm text-yellow-700">
        {{ pausedReason }}
      </div>

      <!-- 分页 -->
      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </div>

    <!-- 批次追踪弹窗 -->
    <el-drawer v-model="batchDrawerVisible" size="60%" :title="`批次追踪 - ${currentTask?.taskNo || ''}`">
      <template v-if="currentTask">
        <!-- 任务级统计 -->
        <div class="grid grid-cols-4 gap-3 mb-4">
          <div class="bg-gray-50 rounded-lg p-3 text-center">
            <p class="text-xl font-bold text-gray-600">{{ pendingOf(currentTask) }}</p>
            <p class="text-gray-500 text-xs">待上送</p>
          </div>
          <div class="bg-blue-50 rounded-lg p-3 text-center">
            <p class="text-xl font-bold text-blue-600">{{ sendingOf(currentTask) }}</p>
            <p class="text-gray-500 text-xs">上送中</p>
          </div>
          <div class="bg-green-50 rounded-lg p-3 text-center">
            <p class="text-xl font-bold text-green-600">{{ currentTask.successCount || 0 }}</p>
            <p class="text-gray-500 text-xs">成功</p>
          </div>
          <div class="bg-red-50 rounded-lg p-3 text-center">
            <p class="text-xl font-bold text-red-600">{{ currentTask.failCount || 0 }}</p>
            <p class="text-gray-500 text-xs">失败</p>
          </div>
        </div>

        <el-alert
          :title="`失败策略：${currentTask.continueOnFail === 1 ? '某批失败后继续后续批次' : '某批失败后暂停任务，等待人工处理'}`"
          :type="currentTask.continueOnFail === 1 ? 'warning' : 'info'"
          :closable="false" class="mb-4" />

        <el-table :data="batches" stripe size="small" max-height="600">
          <el-table-column label="批次" width="70">
            <template #default="{ row }">#{{ row.batchIndex }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="batchStatusType(row.status)" size="small">
                {{ batchStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="条数" width="70" align="center" prop="totalCount" />
          <el-table-column label="成功" width="70" align="center">
            <template #default="{ row }">
              <span class="text-green-600">{{ row.successCount || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="失败" width="70" align="center">
            <template #default="{ row }">
              <span :class="(row.failCount || 0) > 0 ? 'text-red-600' : ''">{{ row.failCount || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="HTTP" width="70" align="center">
            <template #default="{ row }">
              <span v-if="row.httpStatus" :class="httpClass(row.httpStatus)">{{ row.httpStatus }}</span>
              <span v-else class="text-gray-300">-</span>
            </template>
          </el-table-column>
          <el-table-column label="平台流水号" min-width="200">
            <template #default="{ row }">
              <span class="font-mono text-xs">{{ row.traceId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="80" align="center">
            <template #default="{ row }">{{ duration(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="viewTrace(row)">报文</el-button>
              <el-button
                v-if="row.status === 'FAILED' || row.status === 'PARTIAL'"
                type="danger" link size="small" @click="retryBatch(row, currentTask)">重试</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 请求/响应报文追踪弹窗 -->
    <el-dialog v-model="traceVisible" title="批次请求/响应追踪" width="760px" append-to-body>
      <div v-if="traceBatch" class="space-y-4">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="批次">#{{ traceBatch.batchIndex }}</el-descriptions-item>
          <el-descriptions-item label="HTTP状态码">
            <span :class="httpClass(traceBatch.httpStatus)">{{ traceBatch.httpStatus || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="平台流水号">
            <span class="font-mono text-xs">{{ traceBatch.traceId || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="条数">{{ traceBatch.totalCount }}</el-descriptions-item>
          <el-descriptions-item label="成功">{{ traceBatch.successCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ traceBatch.failCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="开始时间" :span="3">{{ formatTime(traceBatch.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间" :span="3">{{ formatTime(traceBatch.endTime) }}</el-descriptions-item>
          <el-descriptions-item v-if="traceBatch.errorMessage" label="错误信息" :span="3">
            <span class="text-red-600">{{ traceBatch.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div>
          <div class="flex items-center justify-between mb-1">
            <span class="font-medium text-gray-700 text-sm">请求报文（上送国家平台）</span>
            <el-button link size="small" @click="copy(traceBatch.requestBody)">复制</el-button>
          </div>
          <pre class="trace-box">{{ traceBatch.requestBody || '（暂无，批次尚未开始）' }}</pre>
        </div>

        <div>
          <div class="flex items-center justify-between mb-1">
            <span class="font-medium text-gray-700 text-sm">响应报文（国家平台返回）</span>
            <el-button link size="small" @click="copy(traceBatch.responseBody)">复制</el-button>
          </div>
          <pre class="trace-box">{{ formatResponseBody(traceBatch.responseBody) }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportTaskApi } from '@/api'

const loading = ref(false)
const tasks = ref([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })
let timer = null

const batchDrawerVisible = ref(false)
const currentTask = ref(null)
const batches = ref([])

const traceVisible = ref(false)
const traceBatch = ref(null)

const pausedReason = computed(() => {
  const t = tasks.value.find(x => x.status === 'PAUSED')
  return t?.errorMessage || ''
})

// 待上送 = 总数 - 成功 - 失败 - 上送中
const pendingOf = (row) => {
  const total = row.totalCount || 0
  const done = (row.successCount || 0) + (row.failCount || 0) + sendingOf(row)
  return Math.max(0, total - done)
}
const sendingOf = (row) => row.status === 'RUNNING' ? row._sending || 0 : 0
const doneBatches = (row) => {
  // 用条目进度近似展示批次进度
  const done = (row.successCount || 0) + (row.failCount || 0)
  return row.totalBatches
    ? Math.min(row.totalBatches, Math.floor(done / (row.batchSize || 1)))
    : 0
}
const batchPercentage = (row) => {
  const total = row.totalCount || 0
  if (!total) return 0
  const done = (row.successCount || 0) + (row.failCount || 0)
  return Math.round((done / total) * 100)
}
const progressStatus = (row) => {
  if (row.status === 'SUCCESS') return 'success'
  if (row.status === 'FAILED') return 'exception'
  if (row.status === 'PARTIAL' || row.status === 'PAUSED') return 'warning'
  return ''
}
const canRetry = (row) => (row.failCount || 0) > 0 &&
  ['PAUSED', 'PARTIAL', 'FAILED', 'SUCCESS'].includes(row.status)

const taskStatusMap = {
  PENDING: { text: '排队中', type: 'info' },
  RUNNING: { text: '上送中', type: 'primary' },
  PAUSED: { text: '已暂停', type: 'warning' },
  SUCCESS: { text: '成功', type: 'success' },
  PARTIAL: { text: '部分成功', type: 'warning' },
  FAILED: { text: '失败', type: 'danger' }
}
const taskStatusText = (s) => taskStatusMap[s]?.text || s
const taskStatusType = (s) => taskStatusMap[s]?.type || 'info'

const batchStatusMap = {
  PENDING: { text: '待上送', type: 'info' },
  RUNNING: { text: '上送中', type: 'primary' },
  SUCCESS: { text: '成功', type: 'success' },
  PARTIAL: { text: '部分成功', type: 'warning' },
  FAILED: { text: '失败', type: 'danger' }
}
const batchStatusText = (s) => batchStatusMap[s]?.text || s
const batchStatusType = (s) => batchStatusMap[s]?.type || 'info'

const httpClass = (code) => {
  if (!code) return ''
  if (code >= 500) return 'text-red-600 font-semibold'
  if (code >= 400) return 'text-orange-500 font-semibold'
  return 'text-green-600 font-medium'
}

const formatTime = (t) => (t ? new Date(t).toLocaleString('zh-CN') : '-')

const duration = (row) => {
  if (!row.startTime || !row.endTime) return '-'
  const ms = new Date(row.endTime) - new Date(row.startTime)
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

const formatResponseBody = (body) => {
  if (!body) return '（暂无，批次尚未完成或网络异常无响应）'
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

const copy = async (text) => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

const fetchTasks = async () => {
  loading.value = true
  try {
    const res = await reportTaskApi.page({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    })
    tasks.value = res.data.records || []
    pagination.total = res.data.total || 0
    // 对运行中的任务精确拉取“上送中”数量
    await Promise.all(tasks.value.filter(t => t.status === 'RUNNING').map(async (t) => {
      try {
        const r = await reportTaskApi.statusCount(t.batchNo)
        t._sending = r.data.sending || 0
      } catch { /* ignore */ }
    }))
  } catch { /* handled */ } finally {
    loading.value = false
  }
}

const fetchBatches = async () => {
  if (!currentTask.value) return
  const [taskRes, batchRes] = await Promise.all([
    reportTaskApi.detail(currentTask.value.id),
    reportTaskApi.batches(currentTask.value.id)
  ])
  currentTask.value = taskRes.data
  if (currentTask.value.status === 'RUNNING') {
    const r = await reportTaskApi.statusCount(currentTask.value.batchNo)
    currentTask.value._sending = r.data.sending || 0
  }
  batches.value = batchRes.data || []
  // 同步列表中的任务数据
  const idx = tasks.value.findIndex(t => t.id === currentTask.value.id)
  if (idx >= 0) tasks.value[idx] = { ...currentTask.value }
}

const viewBatches = async (row) => {
  currentTask.value = row
  batches.value = []
  batchDrawerVisible.value = true
  await fetchBatches()
}

const viewTrace = (row) => {
  traceBatch.value = row
  traceVisible.value = true
}

const pause = async (row) => {
  try {
    await ElMessageBox.confirm('确定暂停该上送任务吗？正在执行的批次完成后会停止后续批次。', '暂停确认', {
      confirmButtonText: '暂停', cancelButtonText: '取消', type: 'warning'
    })
    await reportTaskApi.pause(row.id)
    ElMessage.success('任务将在当前批次完成后暂停')
    fetchTasks()
  } catch { /* cancel */ }
}

const resume = async (row) => {
  await reportTaskApi.resume(row.id)
  ElMessage.success('任务已继续，剩余批次重新入队')
  fetchTasks()
}

const retryFailed = async (row) => {
  try {
    await ElMessageBox.confirm(
      `将该任务下 ${row.failCount} 条失败数据重新入队上送，是否继续？`,
      '重试确认', { confirmButtonText: '重试', cancelButtonText: '取消', type: 'warning' })
    await reportTaskApi.retryFailed(row.id)
    ElMessage.success('失败数据已重新入队')
    fetchTasks()
    if (currentTask.value?.id === row.id) fetchBatches()
  } catch { /* cancel */ }
}

const retryBatch = async (batch, task) => {
  try {
    await ElMessageBox.confirm(`确定重试第 #${batch.batchIndex} 批中的失败数据吗？`, '批次重试', {
      confirmButtonText: '重试', cancelButtonText: '取消', type: 'warning'
    })
    await reportTaskApi.retryBatch(batch.id)
    ElMessage.success('批次已重新入队')
    if (task.status === 'PAUSED' || ['PARTIAL', 'FAILED', 'SUCCESS'].includes(task.status)) {
      ElMessage.info('任务已恢复执行')
    }
    await fetchBatches()
    fetchTasks()
  } catch { /* cancel */ }
}

const handleSizeChange = (s) => { pagination.pageSize = s; fetchTasks() }
const handleCurrentChange = (p) => { pagination.pageNum = p; fetchTasks() }

onMounted(async () => {
  await fetchTasks()
  // 存在未完成任务时自动轮询
  timer = setInterval(() => {
    const hasActive = tasks.value.some(t => ['PENDING', 'RUNNING'].includes(t.status))
    if (hasActive || batchDrawerVisible.value) {
      fetchTasks()
      if (batchDrawerVisible.value) fetchBatches()
    }
  }, 2000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.trace-box {
  background: #1e293b;
  color: #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
