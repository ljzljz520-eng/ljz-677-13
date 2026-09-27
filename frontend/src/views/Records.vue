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
        <el-table-column label="操作" width="230" fixed="right">
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
                @click="openEnqueueDialog(row)"
              >
                加入上送队列
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
      v-model="enqueueVisible"
      title="加入上送队列"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px">
        <el-form-item label="导入批次">
          <span class="font-mono text-sm">{{ enqueueForm.batchNo }}</span>
        </el-form-item>
        <el-form-item label="待上送数据">
          <span class="text-blue-600 font-medium">{{ statusCount.total || 0 }} 条</span>
          <span class="text-xs text-gray-400 ml-2">
            （成功{{ statusCount.success || 0 }} / 失败{{ statusCount.failed || 0 }}）
          </span>
        </el-form-item>
        <el-form-item label="每批条数">
          <el-input-number v-model="enqueueForm.batchSize" :min="1" :max="5000" :step="50" />
          <span class="text-xs text-gray-400 ml-2">
            预计 {{ Math.ceil((statusCount.pending || 0) / (enqueueForm.batchSize || 1)) }} 批
          </span>
        </el-form-item>
        <el-form-item label="批间间隔">由后端统一配置，避免压垮平台</el-form-item>
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
        title="任务加入队列后异步执行，页面不会卡住；可在“上送队列”查看实时进度，每批请求和响应均可追踪。"
      />
      <template #footer>
        <div class="flex justify-end space-x-3">
          <el-button @click="enqueueVisible = false">取消</el-button>
          <el-button type="primary" :loading="enqueuing" @click="confirmEnqueue">
            确认加入队列
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request, { reportTaskApi } from '@/api'

const router = useRouter()

const loading = ref(false)
const records = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const enqueueVisible = ref(false)
const enqueuing = ref(false)
const statusCount = reactive({ total: 0, pending: 0, sending: 0, success: 0, failed: 0 })
const enqueueForm = reactive({
  batchNo: '',
  batchSize: 100,
  continueOnFail: false
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
    const res = await request.get('/excel/records', {
      params: {
        pageNum: pagination.pageNum,
        pageSize: pagination.pageSize
      }
    })
    records.value = res.data.records || []
    pagination.total = res.data.total || 0
  } catch {
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

const openEnqueueDialog = async (row) => {
  enqueueForm.batchNo = row.batchNo
  enqueueForm.batchSize = 100
  enqueueForm.continueOnFail = false
  Object.assign(statusCount, { total: 0, pending: 0, sending: 0, success: 0, failed: 0 })
  enqueueVisible.value = true

  // 若已有任务，提示并跳转
  try {
    const existed = await reportTaskApi.byBatchNo(row.batchNo)
    if (existed.data && ['PENDING', 'RUNNING', 'PAUSED'].includes(existed.data.status)) {
      enqueueVisible.value = false
      ElMessage.info('该批次已在队列中，正在打开上送队列...')
      router.push('/report-tasks')
      return
    }
  } catch { /* ignore */ }

  try {
    const res = await reportTaskApi.statusCount(row.batchNo)
    Object.assign(statusCount, res.data)
  } catch { /* ignore */ }
}

const confirmEnqueue = async () => {
  if (statusCount.pending <= 0) {
    ElMessage.warning('没有待上送的数据')
    return
  }
  enqueuing.value = true
  try {
    await reportTaskApi.create({
      batchNo: enqueueForm.batchNo,
      batchSize: enqueueForm.batchSize,
      continueOnFail: enqueueForm.continueOnFail
    })
    ElMessage.success('已加入上送队列，正在异步上送')
    enqueueVisible.value = false
    router.push('/report-tasks')
  } catch {
    // 拦截器已提示
  } finally {
    enqueuing.value = false
  }
}

onMounted(() => {
  fetchRecords()
})
</script>
