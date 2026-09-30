<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">能耗记录 <em>电价版本 / 自然日分段 / 分段费用快照</em></h2>
      <div class="page-tools">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          size="small"
          style="width: 140px"
          @change="loadAll"
        />
        <el-button size="small" @click="openPriceDialog">登记电价版本</el-button>
        <el-button type="primary" size="small" @click="openCreate">新增抄表</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">{{ summary.month }} 总用电</div>
        <div class="stat-value">{{ summary.totalUsage }}<small>kWh</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">{{ summary.month }} 总电费</div>
        <div class="stat-value">¥ {{ summary.totalFee }}<small>元</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">抄表记录</div>
        <div class="stat-value">{{ summary.recordCount }}<small>条</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">纳入统计机柜</div>
        <div class="stat-value">{{ summary.cabinetCount }}<small>台</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        电价版本与生效时间轴
        <span class="qbe-badge">历史版本 · 当前版本 · 未来版本；已生效价格只允许由新版本接续</span>
      </div>
      <div v-if="priceVersions.length" class="timeline">
        <div
          v-for="item in priceVersions"
          :key="item.id"
          class="price-node"
          :class="`is-${item.status.toLowerCase()}`"
        >
          <div class="price-dot" />
          <div class="price-card">
            <div class="price-head">
              <strong>{{ item.name }}</strong>
              <el-tag size="small" :type="priceStatusType(item.status)" effect="plain">
                {{ priceStatusName(item.status) }}
              </el-tag>
            </div>
            <div class="price-date">
              {{ item.effectiveDate }} 起
              <span v-if="item.status === 'HISTORY'">，至 {{ item.endDate }}</span>
              <span v-else-if="item.endDate">，登记至 {{ item.endDate }}</span>
              <span v-else>，长期有效</span>
            </div>
            <div class="price-main">¥ {{ item.baseUnitPrice }} / kWh</div>
            <div class="price-note">{{ item.billingNote }}</div>
            <div class="price-meta">启用人：{{ item.enabledBy }}</div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无电价版本" :image-size="70" />
    </div>

    <div class="panel">
      <div class="panel-title">
        机柜用电排行 <span class="qbe-badge">费用来自已固化抄表账单，与明细和月度汇总同口径</span>
      </div>
      <ul class="rank">
        <li v-for="row in summary.ranking || []" :key="row.cabinetId">
          <span class="rank-name">{{ row.cabinetCode }}</span>
          <span class="rank-bar">
            <i :style="{ width: barWidth(row.usageKwh) + '%' }" />
          </span>
          <span class="rank-val">{{ row.usageKwh }} kWh</span>
          <span class="rank-fee">¥ {{ row.fee }}</span>
        </li>
      </ul>
    </div>

    <div class="panel">
      <div class="panel-title">
        抄表明细 <span class="qbe-badge">展开可查看日期范围、分摊电量、版本快照与分段费用</span>
      </div>
      <el-form :inline="true" size="small" class="filter">
        <el-form-item label="机柜">
          <el-select v-model="query.cabinetId" clearable placeholder="全部" style="width: 150px">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抄表人">
          <el-input v-model="query.meterReader" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="用电量">
          <el-input-number v-model="query.minUsage" :min="0" :step="100" controls-position="right" style="width: 110px" />
          <span class="muted til">~</span>
          <el-input-number v-model="query.maxUsage" :min="0" :step="100" controls-position="right" style="width: 110px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" size="small" border stripe row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="segment-detail">
              <template v-if="row.billingType === 'LEGACY'">
                <el-alert
                  type="info"
                  show-icon
                  :closable="false"
                  title="历史固定计费：保留当时手填单价和费用，不反推价段，也不随当前电价变化。"
                />
              </template>
              <template v-else-if="!row.priceSegments || !row.priceSegments.length">
                <el-alert
                  type="success"
                  show-icon
                  :closable="false"
                  title="功能启用后的首条读数建账：无上期读数，本期用电量与费用为 0；后续读数开始分段。"
                />
              </template>
              <template v-else>
                <div class="segment-range">
                  计费区间：{{ row.periodStart }} ~ {{ row.meterDate }}
                  <span>共 {{ row.priceSegments.length }} 个价段</span>
                </div>
                <table class="segment-table">
                  <thead>
                    <tr>
                      <th>序号</th>
                      <th>日期范围</th>
                      <th>自然日</th>
                      <th>电价版本</th>
                      <th>版本生效日</th>
                      <th>单价</th>
                      <th>分摊用电量</th>
                      <th>分段费用</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="seg in row.priceSegments" :key="seg.id">
                      <td>{{ seg.segmentOrder }}</td>
                      <td>{{ seg.startDate }} ~ {{ seg.endDate }}</td>
                      <td>{{ seg.dayCount }} 天</td>
                      <td>{{ seg.versionName }}</td>
                      <td>{{ seg.effectiveDate }}</td>
                      <td>¥ {{ seg.unitPrice }}</td>
                      <td>{{ seg.usageKwh }} kWh</td>
                      <td>¥ {{ seg.segmentFee }}</td>
                    </tr>
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colspan="6">汇总（与抄表记录一致）</td>
                      <td>{{ row.usageKwh }} kWh</td>
                      <td>¥ {{ row.fee }}</td>
                    </tr>
                  </tfoot>
                </table>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="抄表日期" width="120" prop="meterDate" />
        <el-table-column label="机柜" min-width="180">
          <template #default="{ row }">
            {{ cabinetName(row.cabinetId) }}
            <span class="muted">{{ cabinetRoom(row.cabinetId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="电表读数" width="120">
          <template #default="{ row }">{{ row.meterReading }} kWh</template>
        </el-table-column>
        <el-table-column label="用电量" width="110">
          <template #default="{ row }">
            <b>{{ row.usageKwh }}</b> kWh
          </template>
        </el-table-column>
        <el-table-column label="计费" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.billingType === 'LEGACY' ? 'info' : 'success'">
              {{ row.billingType === 'LEGACY' ? '历史固定' : '版本分段' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="展示单价" width="100">
          <template #default="{ row }">
            <span v-if="row.unitPrice != null">¥ {{ row.unitPrice }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="电费" width="110">
          <template #default="{ row }">
            <b class="fee">¥ {{ row.fee }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="meterReader" label="抄表人" width="90" />
        <el-table-column prop="remark" label="备注" min-width="160" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="reload"
          @size-change="reload"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" title="新增抄表" width="760px">
      <div class="dialog-grid">
        <el-form :model="form" label-width="100px" size="small">
          <el-form-item label="机柜" required>
            <el-select v-model="form.cabinetId" style="width: 100%" @change="loadPreview">
              <el-option
                v-for="item in cabinets"
                :key="item.id"
                :value="item.id"
                :label="`${item.code} · ${item.name}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="抄表日期" required>
            <el-date-picker
              v-model="form.meterDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              @change="loadPreview"
            />
          </el-form-item>
          <el-form-item label="电表读数" required>
            <el-input-number
              v-model="form.meterReading"
              :min="0"
              :precision="2"
              :step="100"
              style="width: 100%"
              @change="loadPreview"
            />
          </el-form-item>
          <el-form-item label="抄表人"><el-input v-model="form.meterReader" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" /></el-form-item>
        </el-form>

        <div class="preview-box">
          <div class="preview-title">计费预览</div>
          <el-empty v-if="!previewReady" description="选择机柜、日期和读数后预览" :image-size="60" />
          <template v-else>
            <div class="preview-summary">
              <div>
                <span>上期读数</span>
                <strong>{{ preview.previousReading == null ? '无' : preview.previousReading }}</strong>
              </div>
              <div>
                <span>本期区间</span>
                <strong>{{ preview.periodStart || '建账日' }} ~ {{ preview.meterDate }}</strong>
              </div>
              <div>
                <span>预计用电</span>
                <strong>{{ preview.usageKwh }} kWh</strong>
              </div>
              <div>
                <span>预计电费</span>
                <strong class="fee">¥ {{ preview.totalFee }}</strong>
              </div>
            </div>
            <el-table v-if="preview.segments && preview.segments.length" :data="preview.segments" size="small" border>
              <el-table-column label="日期范围" min-width="170">
                <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
              </el-table-column>
              <el-table-column prop="versionName" label="命中版本" min-width="130" />
              <el-table-column label="日数" width="64">
                <template #default="{ row }">{{ row.dayCount }}</template>
              </el-table-column>
              <el-table-column label="单价" width="78">
                <template #default="{ row }">¥{{ row.unitPrice }}</template>
              </el-table-column>
              <el-table-column label="电量" width="92">
                <template #default="{ row }">{{ row.usageKwh }}</template>
              </el-table-column>
              <el-table-column label="费用" width="86">
                <template #default="{ row }">¥{{ row.segmentFee }}</template>
              </el-table-column>
            </el-table>
            <el-alert
              v-else
              type="success"
              :closable="false"
              show-icon
              title="首条读数建账，无上期用量；确认后从下一条抄表开始分段。"
            />
            <p class="rule-tip">确认保存时会按此预览固化版本名称、单价、日期范围、分摊电量和分段费用。</p>
          </template>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确认并保存快照</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceDialog" title="登记电价版本" width="520px">
      <el-form :model="priceForm" label-width="100px" size="small">
        <el-form-item label="版本名称" required>
          <el-input v-model="priceForm.name" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="生效日期" required>
          <el-date-picker
            v-model="priceForm.effectiveDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="基础单价" required>
          <el-input-number
            v-model="priceForm.baseUnitPrice"
            :min="0.0001"
            :precision="4"
            :step="0.01"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="计费说明" required>
          <el-input v-model="priceForm.billingNote" type="textarea" :rows="3" maxlength="300" show-word-limit />
        </el-form-item>
        <el-form-item label="启用人" required>
          <el-input v-model="priceForm.enabledBy" maxlength="30" />
        </el-form-item>
      </el-form>
      <p class="rule-tip">未来版本可提前登记；若生效日落入已形成费用的抄表区间，系统会拒绝并列出受影响记录。</p>
      <template #footer>
        <el-button @click="priceDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingPrice" @click="submitPrice">登记版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cabinetApi, currentMonth, energyApi } from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const month = ref(currentMonth())
const priceVersions = ref([])
const summary = ref({ month: month.value, totalUsage: '0.00', totalFee: '0.00', recordCount: 0, cabinetCount: 0, ranking: [] })

const query = reactive({ cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1, size: 10 })
const dialog = ref(false)
const saving = ref(false)
const form = ref(emptyForm())
const preview = ref(null)
const previewTimer = ref(null)

const priceDialog = ref(false)
const savingPrice = ref(false)
const priceForm = ref(emptyPriceForm())

function emptyForm() {
  return { cabinetId: null, meterDate: null, meterReading: 0, meterReader: '', remark: '' }
}

function emptyPriceForm() {
  return { name: '', effectiveDate: null, baseUnitPrice: 0.68, billingNote: '', enabledBy: '' }
}

const cabinetName = (id) => cabinets.value.find((item) => item.id === id)?.code || '—'
const cabinetRoom = (id) => cabinets.value.find((item) => item.id === id)?.roomName || ''
const previewReady = computed(() => !!preview.value)

const maxUsage = computed(() =>
  (summary.value.ranking || []).reduce((max, row) => Math.max(max, Number(row.usageKwh) || 0), 0)
)

const barWidth = (value) => {
  if (!maxUsage.value) return 0
  return Math.round(((Number(value) || 0) * 100) / maxUsage.value)
}

const priceStatusName = (status) => ({ HISTORY: '历史版本', CURRENT: '当前版本', FUTURE: '未来版本' })[status] || status
const priceStatusType = (status) => ({ HISTORY: 'info', CURRENT: 'success', FUTURE: 'warning' })[status] || 'info'

async function reload() {
  const data = await energyApi.search({
    cabinetId: query.cabinetId || undefined,
    meterReader: query.meterReader || undefined,
    minUsage: query.minUsage,
    maxUsage: query.maxUsage,
    month: month.value,
    page: query.page - 1,
    size: query.size
  })
  rows.value = data.content
  total.value = data.total
}

async function loadSummary() {
  summary.value = await energyApi.summary(month.value)
}

async function loadPriceVersions() {
  priceVersions.value = await energyApi.priceVersions()
}

function reset() {
  Object.assign(query, { cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1 })
  reload()
}

function openCreate() {
  form.value = emptyForm()
  if (cabinets.value.length) form.value.cabinetId = cabinets.value[0].id
  preview.value = null
  dialog.value = true
  setTimeout(loadPreview, 0)
}

function openPriceDialog() {
  priceForm.value = { ...emptyPriceForm(), effectiveDate: null }
  priceDialog.value = true
}

function loadPreview() {
  clearTimeout(previewTimer.value)
  const payload = form.value
  if (!payload.cabinetId || !payload.meterDate || payload.meterReading == null) {
    preview.value = null
    return
  }
  previewTimer.value = setTimeout(async () => {
    try {
      preview.value = await energyApi.preview({
        cabinetId: payload.cabinetId,
        meterDate: payload.meterDate,
        meterReading: payload.meterReading
      })
    } catch (e) {
      preview.value = null
    }
  }, 250)
}

async function submit() {
  if (!form.value.cabinetId || !form.value.meterDate || form.value.meterReading == null) {
    ElMessage.warning('请选择机柜、抄表日期并填写读数')
    return
  }
  saving.value = true
  try {
    const saved = await energyApi.create(form.value)
    ElMessage.success(`抄表记录已登记，已固化 ${saved.priceSegments?.length || 0} 个分段快照`)
    dialog.value = false
    month.value = String(form.value.meterDate).slice(0, 7)
    query.page = 1
    await loadAll()
  } finally {
    saving.value = false
  }
}

async function submitPrice() {
  if (!priceForm.value.name || !priceForm.value.effectiveDate || !priceForm.value.enabledBy || !priceForm.value.billingNote) {
    ElMessage.warning('请完整填写版本名称、生效日期、计费说明和启用人')
    return
  }
  savingPrice.value = true
  try {
    await energyApi.createPriceVersion(priceForm.value)
    ElMessage.success('电价版本已登记')
    priceDialog.value = false
    await loadAll()
  } finally {
    savingPrice.value = false
  }
}

function loadAll() {
  return Promise.all([reload(), loadSummary(), loadPriceVersions()])
}

onMounted(async () => {
  cabinets.value = await cabinetApi.cabinets()
  await loadAll()
})
</script>

<style scoped>
.filter {
  margin-bottom: -8px;
}

.til {
  margin: 0 6px;
}

.fee {
  color: #c62828;
}

.timeline {
  position: relative;
  padding: 6px 0 2px 8px;
}

.timeline::before {
  content: '';
  position: absolute;
  left: 20px;
  top: 14px;
  bottom: 14px;
  width: 2px;
  background: #d7dde2;
}

.price-node {
  position: relative;
  padding: 0 0 14px 38px;
}

.price-dot {
  position: absolute;
  left: 12px;
  top: 12px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 3px solid #90a4ae;
  background: #fff;
  z-index: 1;
}

.price-node.is-current .price-dot {
  border-color: #2e7d32;
  background: #e8f5e9;
  box-shadow: 0 0 0 4px rgba(46, 125, 50, 0.12);
}

.price-node.is-future .price-dot {
  border-color: #ed6c02;
  background: #fff3e0;
}

.price-card {
  border: 1px solid #e0e4e8;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fff;
}

.price-node.is-current .price-card {
  border-color: #66bb6a;
  background: #f7fff8;
}

.price-node.is-future .price-card {
  border-style: dashed;
  background: #fffdf8;
}

.price-head,
.preview-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.price-date,
.price-meta {
  margin-top: 4px;
  color: var(--ink-2);
  font-size: 12px;
}

.price-main {
  margin-top: 6px;
  font-size: 20px;
  font-weight: 700;
  color: #c62828;
}

.price-note {
  margin-top: 4px;
  color: #546e7a;
  font-size: 12px;
  line-height: 1.5;
}

.rank {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rank li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  font-size: 12px;
}

.rank-name {
  flex: 0 0 56px;
  font-weight: 600;
}

.rank-bar {
  flex: 1;
  height: 12px;
  background: #eceff1;
  border-radius: 999px;
  overflow: hidden;
}

.rank-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #37474f, #62727b);
}

.rank-val {
  flex: 0 0 110px;
  text-align: right;
  color: var(--ink-2);
}

.rank-fee {
  flex: 0 0 100px;
  text-align: right;
  color: #c62828;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.segment-detail {
  padding: 12px 18px 16px;
  background: #fafbfc;
}

.segment-range {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--ink-2);
}

.segment-range span {
  margin-left: 12px;
}

.segment-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  background: #fff;
}

.segment-table th,
.segment-table td {
  border: 1px solid #e2e6ea;
  padding: 7px 8px;
  text-align: left;
}

.segment-table th {
  background: #f1f4f6;
}

.segment-table tfoot {
  font-weight: 700;
}

.dialog-grid {
  display: grid;
  grid-template-columns: 1fr 1.08fr;
  gap: 16px;
  align-items: start;
}

.preview-box {
  min-height: 280px;
  border: 1px solid #dfe4e8;
  border-radius: 8px;
  padding: 12px;
  background: #fafbfc;
}

.preview-title {
  margin-bottom: 10px;
  font-weight: 700;
}

.preview-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 10px;
}

.preview-summary div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px;
  border-radius: 6px;
  background: #fff;
  border: 1px solid #e5e9ed;
  font-size: 12px;
  color: var(--ink-2);
}

.preview-summary strong {
  color: var(--ink-1);
  font-size: 13px;
}
</style>
