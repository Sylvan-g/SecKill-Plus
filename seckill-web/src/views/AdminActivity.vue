<template>
  <div class="admin">
    <el-card shadow="never">
      <h2>运营端 · 创建秒杀活动</h2>
      <el-form :model="form" label-width="110px" style="max-width: 560px" v-loading="submitting">
        <el-form-item label="商品ID">
          <el-input-number v-model="form.goodsId" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="商品名称">
          <el-input v-model="form.goodsName" placeholder="如：T12-无线降噪耳机" />
        </el-form-item>
        <el-form-item label="商品图片">
          <el-input v-model="form.goodsImg" placeholder="可空，展示图 URL" />
        </el-form-item>
        <el-form-item label="原价">
          <el-input-number v-model="form.originalPrice" :min="0.01" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="秒杀价">
          <el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="限购">
          <el-input-number v-model="form.limitPerUser" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开抢时间" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="create">创建活动</el-button>
          <el-button @click="$router.push('/admin/log')">查看秒杀日志</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'

const router = useRouter()
const submitting = ref(false)
const form = reactive({
  goodsId: undefined,
  goodsName: '',
  goodsImg: '',
  originalPrice: 99.0,
  seckillPrice: 49.0,
  stock: 100,
  limitPerUser: 1,
  startTime: '',
  endTime: ''
})

async function create() {
  if (!form.goodsId || !form.goodsName || !form.startTime || !form.endTime) {
    ElMessage.warning('请填写完整（商品ID/名称/起止时间必填）')
    return
  }
  submitting.value = true
  try {
    const res = await adminApi.createActivity({
      goodsId: form.goodsId,
      goodsName: form.goodsName,
      goodsImg: form.goodsImg || null,
      originalPrice: form.originalPrice,
      seckillPrice: form.seckillPrice,
      stock: form.stock,
      limitPerUser: form.limitPerUser,
      startTime: form.startTime,
      endTime: form.endTime
    })
    ElMessage.success(`创建成功，活动ID=${res.data}`)
    router.push('/admin/log')
  } catch (e) {
    // 参数校验/5000 已在请求层提示
  } finally {
    submitting.value = false
  }
}
</script>