<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">能耗记录 <em>电价版本化计费 · 跨价段按自然日拆段 · 快照固化</em></h2>
      <div class="page-tools">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          size="small"
          style="width: 140px"
          @change="loadAll"
        />
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

    <!-- 电价版本：时间轴 + 版本列表 -->
    <div class="panel tariff-panel">
      <div class="panel-title">
        电价版本与生效时间轴
        <span class="qbe-badge">同一日期全站仅一个生效版本 · 生效版本价格锁定，由新版本接续</span>
        <el-button type="primary" size="small" class="tariff-add" @click="openTariffCreate">登记新版本</el-button>
      </div>

      <div class="tariff-wrap">
        <ul class="timeline">
          <li v-for="v in tariffVersions" :key="v.id" :class="`phase-${v.phase.toLowerCase()}`">
            <span class="dot" />
            <div class="tl-card">
              <div class="tl-head">
                <b>{{ v.name }}</b>
                <el-tag :type="phaseTag(v.phase)" size="small" effect="plain">{{ phaseName(v.phase) }}</el-tag>
                <em v-if="v.referenced" class="locked">已被账单引用</em>
              </div>
              <div class="tl-body">
                <span class="price">¥ {{ v.basePrice }}</span>
                <span class="muted">/ kWh</span>
                <span class="range">
                  {{ v.effectiveDate }}
                  <template v-if="v.endDate"> ～ {{ v.endDate }}</template>
                  <template v-else> 起长期有效</template>
                </span>
              </div>
              <div class="tl-note">{{ v.billingNote }}</div>
              <div class="tl-foot muted">启用人：{{ v.enabledBy }}</div>
              <div class="tl-actions">
                <el-button link type="primary" size="small" @click="openTariffEdit(v)">调整</el-button>
                <el-popconfirm
                  v-if="v.phase === 'FUTURE' && !v.referenced"
                  title="确认删除该未来版本？（已生效或已引用版本不可删）"
                  @confirm="removeTariff(v)"
                >
                  <template #reference>
                    <el-button link type="danger" size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        机柜用电排行 <span class="qbe-badge">直接汇总抄表固化费用，与明细 / 分段同一套数字</span>
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
        抄表明细 <span class="qbe-badge">展开可见各价段日期 / 分摊电量 / 采用版本 / 分段费用</span>
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

      <el-table
        :data="rows"
        size="small"
        border
        stripe
        row-key="id"
        :expand-row-keys="expandedKeys"
        @expand-change="onExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="row.billingType === 'VERSIONED'" class="seg-detail">
              <p class="seg-title">
                跨价段计费快照 · 计费区间
                <b>{{ periodText(row) }}</b>
                <span class="muted">（前次抄表日次日 ～ 本次抄表日，按自然日归属，切换日归新版本）</span>
              </p>
              <table v-if="row.tariffSegments && row.tariffSegments.length" class="seg-table">
                <thead>
                  <tr>
                    <th>价段</th>
                    <th>日期范围</th>
                    <th>天数</th>
                    <th>电价版本</th>
                    <th>单价</th>
                    <th>分摊用电量</th>
                    <th>分段费用</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="seg in row.tariffSegments" :key="seg.id">
                    <td>{{ seg.segNo }}</td>
                    <td>{{ seg.segStart }} ～ {{ seg.segEnd }}</td>
                    <td>{{ seg.dayCount }} 天</td>
                    <td>{{ seg.tariffName }}</td>
                    <td>¥ {{ seg.unitPrice }}</td>
                    <td>{{ seg.allocKwh }} kWh</td>
                    <td class="fee">¥ {{ seg.segFee }}</td>
                  </tr>
                  <tr class="seg-sum">
                    <td colspan="5">合计</td>
                    <td>{{ row.usageKwh }} kWh</td>
                    <td class="fee">¥ {{ row.fee }}</td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="muted">首条建账 / 本期零用电量，无分段费用。</p>
            </div>
            <div v-else class="seg-detail">
              <el-tag type="info" size="small" effect="plain">历史固定计费</el-tag>
              <span class="muted seg-legacy">功能启用前的抄表记录，保留当时单价 ¥{{ row.unitPrice }} 与费用，不反推价段，也不随后续版本调整变化。</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="抄表日期" width="110" prop="meterDate" />
        <el-table-column label="机柜" min-width="160">
          <template #default="{ row }">
            {{ cabinetName(row.cabinetId) }}
            <span class="muted">{{ cabinetRoom(row.cabinetId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="电表读数" width="110">
          <template #default="{ row }">{{ row.meterReading }} kWh</template>
        </el-table-column>
        <el-table-column label="用电量" width="100">
          <template #default="{ row }">
            <b>{{ row.usageKwh }}</b> kWh
          </template>
        </el-table-column>
        <el-table-column label="计费口径" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.billingType === 'VERSIONED'" type="primary" size="small" effect="plain">跨价段</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">历史固定</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="电价" width="120">
          <template #default="{ row }">
            <template v-if="row.billingType === 'VERSIONED'">
              <el-button v-if="row.tariffSegments && row.tariffSegments.length" link type="primary" size="small" @click="toggleExpand(row)">
                {{ row.tariffSegments.length }} 个价段
              </el-button>
              <span v-else-if="row.usageKwh === 0" class="muted">—</span>
            </template>
            <template v-else>¥ {{ row.unitPrice }}</template>
          </template>
        </el-table-column>
        <el-table-column label="电费" width="100">
          <template #default="{ row }">
            <b class="fee">¥ {{ row.fee }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="meterReader" label="抄表人" width="80" />
        <el-table-column prop="remark" label="备注" min-width="140" />
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

    <!-- 新增抄表：先预览分段费用，确认后固化快照 -->
    <el-dialog v-model="dialog" title="新增抄表" width="640px" @closed="resetPreview">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="机柜">
          <el-select v-model="form.cabinetId" style="width: 100%" @change="previewBilling">
            <el-option
              v-for="item in cabinets"
              :key="item.id"
              :value="item.id"
              :label="`${item.code} · ${item.name}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抄表日期">
          <el-date-picker
            v-model="form.meterDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            @change="previewBilling"
          />
        </el-form-item>
        <el-form-item label="电表读数">
          <el-input-number
            v-model="form.meterReading"
            :min="0"
            :precision="2"
            :step="100"
            style="width: 100%"
            @change="previewBilling"
          />
        </el-form-item>
        <el-form-item label="抄表人"><el-input v-model="form.meterReader" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>

      <div class="preview-box">
        <div class="preview-head">
          <span>计费预览</span>
          <el-button size="small" :loading="previewLoading" @click="previewBilling">刷新预览</el-button>
        </div>
        <p v-if="!preview" class="muted">选择机柜、抄表日期并填写读数后，自动预览本次区间命中的电价版本与分段费用。</p>
        <template v-else>
          <p v-if="preview.kind === 'FIRST_RECORD'" class="muted">
            该机柜首次建账，无上期读数，本期用电量与费用为 0；下一次抄表起按版本链跨价段计费。
          </p>
          <template v-else>
            <p class="preview-line">
              计费区间 <b>{{ preview.periodStart }} ～ {{ preview.meterDate }}</b>
              （上期 {{ preview.previousMeterDate }} 读数 {{ preview.previousReading }}），
              预计用电量 <b>{{ preview.usageKwh }} kWh</b>
            </p>
            <table v-if="preview.segments && preview.segments.length" class="seg-table">
              <thead>
                <tr>
                  <th>日期范围</th>
                  <th>天数</th>
                  <th>电价版本</th>
                  <th>单价</th>
                  <th>分摊用电量</th>
                  <th>分段费用</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(seg, i) in preview.segments" :key="i">
                  <td>{{ seg.segStart }} ～ {{ seg.segEnd }}</td>
                  <td>{{ seg.dayCount }} 天</td>
                  <td>{{ seg.tariffName }}</td>
                  <td>¥ {{ seg.unitPrice }}</td>
                  <td>{{ seg.allocKwh }} kWh</td>
                  <td class="fee">¥ {{ seg.segFee }}</td>
                </tr>
                <tr class="seg-sum">
                  <td colspan="4">预计总电费</td>
                  <td>{{ preview.usageKwh }} kWh</td>
                  <td class="fee">¥ {{ preview.totalFee }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">本期用电量为 0，预计费用 ¥ 0.00。</p>
          </template>
        </template>
      </div>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确认并固化计费快照</el-button>
      </template>
    </el-dialog>

    <!-- 登记 / 调整电价版本 -->
    <el-dialog v-model="tariffDialog" :title="tariffForm.id ? '调整电价版本' : '登记电价版本'" width="520px">
      <el-form :model="tariffForm" label-width="100px" size="small">
        <el-form-item label="版本名称">
          <el-input v-model="tariffForm.name" placeholder="如：2026 四季度基础电价" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker
            v-model="tariffForm.effectiveDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="!!tariffForm.id && tariffLocked"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="基础单价">
          <el-input-number
            v-model="tariffForm.basePrice"
            :min="0"
            :precision="3"
            :step="0.01"
            :disabled="!!tariffForm.id && tariffLocked"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="计费说明">
          <el-input v-model="tariffForm.billingNote" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="启用人">
          <el-input v-model="tariffForm.enabledBy" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="tariffForm.id && tariffLocked"
        type="warning"
        :closable="false"
        show-icon
        title="该版本已开始生效或已被抄表快照引用，生效日期与基础单价已锁定；只能调整名称、计费说明与启用人。改价请登记新版本接续。"
        class="lock-alert"
      />
      <p v-else class="rule-tip">
        新版本生效日不能与已有版本同日，也不能插入已经形成费用的抄表区间；未来版本可提前登记。
      </p>
      <template #footer>
        <el-button @click="tariffDialog = false">取消</el-button>
        <el-button type="primary" :loading="tariffSaving" @click="submitTariff">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cabinetApi, currentMonth, energyApi, tariffApi } from '../api'

const rows = ref([])
const total = ref(0)
const cabinets = ref([])
const month = ref(currentMonth())
const summary = ref({ month: month.value, totalUsage: '0.00', totalFee: '0.00', recordCount: 0, cabinetCount: 0, ranking: [] })
const tariffVersions = ref([])

const query = reactive({ cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1, size: 10 })
const expandedKeys = ref([])

const dialog = ref(false)
const submitting = ref(false)
const form = ref(blankForm())
const preview = ref(null)
const previewLoading = ref(false)
let previewSeq = 0

const tariffDialog = ref(false)
const tariffSaving = ref(false)
const tariffForm = ref(blankTariff())
const tariffLocked = ref(false)

function blankForm() {
  return { cabinetId: null, meterDate: null, meterReading: 0, meterReader: '', remark: '' }
}

function blankTariff() {
  return { id: null, name: '', effectiveDate: null, basePrice: 0.7, billingNote: '', enabledBy: '' }
}

const cabinetName = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.code : '—'
}

const cabinetRoom = (id) => {
  const hit = cabinets.value.find((item) => item.id === id)
  return hit ? hit.roomName || '' : ''
}

const maxUsage = computed(() =>
  (summary.value.ranking || []).reduce((max, row) => Math.max(max, Number(row.usageKwh) || 0), 0)
)

const barWidth = (value) => {
  if (!maxUsage.value) return 0
  return Math.round(((Number(value) || 0) * 100) / maxUsage.value)
}

const phaseName = (phase) => ({ CURRENT: '当前版本', FUTURE: '未来版本', HISTORY: '历史版本' })[phase] || phase
const phaseTag = (phase) => ({ CURRENT: 'success', FUTURE: 'warning', HISTORY: 'info' })[phase] || 'info'

const periodText = (row) => {
  if (!row.periodStart) return '首条建账'
  return `${row.periodStart} ～ ${row.meterDate}`
}

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
  // 翻页后保持展开的行
  expandedKeys.value = data.content.filter((r) => expandedKeys.value.includes(r.id)).map((r) => r.id)
}

async function loadSummary() {
  summary.value = await energyApi.summary(month.value)
}

async function loadTariffs() {
  const timeline = await tariffApi.timeline()
  tariffVersions.value = timeline.versions || []
}

function reset() {
  Object.assign(query, { cabinetId: null, meterReader: '', minUsage: null, maxUsage: null, page: 1 })
  reload()
}

function onExpandChange(row, expanded) {
  const isOpen = Array.isArray(expanded) ? expanded.some((r) => r.id === row.id) : expanded
  if (isOpen) {
    if (!expandedKeys.value.includes(row.id)) expandedKeys.value.push(row.id)
  } else {
    expandedKeys.value = expandedKeys.value.filter((id) => id !== row.id)
  }
}

function toggleExpand(row) {
  if (expandedKeys.value.includes(row.id)) {
    expandedKeys.value = expandedKeys.value.filter((id) => id !== row.id)
  } else {
    expandedKeys.value = [...expandedKeys.value, row.id]
  }
}

function openCreate() {
  form.value = blankForm()
  form.value.cabinetId = cabinets.value.length ? cabinets.value[0].id : null
  preview.value = null
  dialog.value = true
  previewBilling()
}

function resetPreview() {
  preview.value = null
}

let previewTimer = null
async function previewBilling() {
  const seq = ++previewSeq
  clearTimeout(previewTimer)
  if (!form.value.cabinetId || !form.value.meterDate || form.value.meterReading == null) {
    preview.value = null
    return
  }
  // 数字输入连续变化时做个轻量去抖
  previewTimer = setTimeout(async () => {
    previewLoading.value = true
    try {
      const data = await energyApi.preview({
        cabinetId: form.value.cabinetId,
        meterDate: form.value.meterDate,
        meterReading: form.value.meterReading
      })
      // 过期的预览响应丢弃，避免后到的旧请求覆盖新结果
      if (seq === previewSeq) preview.value = data
    } catch (e) {
      if (seq === previewSeq) preview.value = null
    } finally {
      if (seq === previewSeq) previewLoading.value = false
    }
  }, 200)
}

async function submit() {
  if (!form.value.cabinetId || !form.value.meterDate) {
    ElMessage.warning('请选择机柜与抄表日期')
    return
  }
  submitting.value = true
  try {
    await energyApi.create({
      cabinetId: form.value.cabinetId,
      meterDate: form.value.meterDate,
      meterReading: form.value.meterReading,
      meterReader: form.value.meterReader,
      remark: form.value.remark
    })
    ElMessage.success('抄表记录已登记，分段计费快照已固化')
    dialog.value = false
    await loadAll()
  } finally {
    submitting.value = false
  }
}

function openTariffCreate() {
  tariffForm.value = blankTariff()
  tariffLocked.value = false
  tariffDialog.value = true
}

function openTariffEdit(v) {
  tariffForm.value = {
    id: v.id,
    name: v.name,
    effectiveDate: v.effectiveDate,
    basePrice: Number(v.basePrice),
    billingNote: v.billingNote,
    enabledBy: v.enabledBy
  }
  // 已生效或已引用：价格 / 生效日锁定
  tariffLocked.value = v.phase !== 'FUTURE' || !!v.referenced
  tariffDialog.value = true
}

async function submitTariff() {
  const f = tariffForm.value
  if (!f.name || !f.effectiveDate || !f.billingNote || !f.enabledBy) {
    ElMessage.warning('请完整填写版本名称、生效日期、计费说明与启用人')
    return
  }
  tariffSaving.value = true
  try {
    if (f.id) {
      await tariffApi.update(f.id, f)
      ElMessage.success('电价版本已调整')
    } else {
      await tariffApi.create(f)
      ElMessage.success('电价版本已登记')
    }
    tariffDialog.value = false
    await loadTariffs()
  } finally {
    tariffSaving.value = false
  }
}

async function removeTariff(v) {
  await tariffApi.remove(v.id)
  ElMessage.success('未来版本已删除')
  await loadTariffs()
}

function loadAll() {
  return Promise.all([reload(), loadSummary(), loadTariffs()])
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

/* 电价版本时间轴 */
.tariff-add {
  margin-left: auto;
}

.tariff-wrap {
  margin-top: 10px;
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 0 8px;
}

.timeline li {
  position: relative;
  padding: 0 0 14px 22px;
  border-left: 2px solid #d7dde2;
}

.timeline li:last-child {
  border-left-color: transparent;
  padding-bottom: 0;
}

.timeline .dot {
  position: absolute;
  left: -7px;
  top: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #90a4ae;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #cfd8dc;
}

.phase-current .dot {
  background: #2e7d32;
  box-shadow: 0 0 0 3px rgba(46, 125, 50, 0.18);
}

.phase-future .dot {
  background: #ed6c02;
}

.tl-card {
  background: #f7f9fa;
  border: 1px solid #e3e8ec;
  border-radius: 6px;
  padding: 8px 12px;
}

.phase-current .tl-card {
  border-color: #a5d6a7;
  background: #f3faf3;
}

.phase-future .tl-card {
  border-style: dashed;
}

.tl-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tl-head .locked {
  font-size: 11px;
  color: #b26a00;
  font-style: normal;
}

.tl-body {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin: 4px 0;
}

.tl-body .price {
  font-size: 18px;
  font-weight: 700;
  color: #c62828;
}

.tl-body .range {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-2);
}

.tl-note {
  font-size: 12px;
  color: var(--ink-2);
}

.tl-foot {
  font-size: 11px;
  margin-top: 2px;
}

.tl-actions {
  margin-top: 2px;
  display: flex;
  gap: 12px;
}

/* 分段明细 */
.seg-detail {
  padding: 8px 18px 10px;
  background: #fafbfc;
}

.seg-title {
  margin: 2px 0 8px;
  font-size: 12px;
}

.seg-legacy {
  margin-left: 8px;
  font-size: 12px;
}

.seg-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.seg-table th,
.seg-table td {
  border: 1px solid #e0e5e9;
  padding: 5px 8px;
  text-align: right;
}

.seg-table th:nth-child(-n + 4),
.seg-table td:nth-child(-n + 4) {
  text-align: left;
}

.seg-table th {
  background: #eef2f4;
  font-weight: 600;
}

.seg-table .seg-sum td {
  font-weight: 700;
  background: #f3f6f7;
}

/* 抄表预览 */
.preview-box {
  margin-top: 4px;
  border: 1px dashed #c3ccd2;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fafbfc;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 6px;
}

.preview-line {
  font-size: 12px;
  margin: 0 0 8px;
}

.lock-alert {
  margin-top: 4px;
}
</style>
