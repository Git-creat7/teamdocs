<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="420px"
    destroy-on-close
    append-to-body
    @open="handleOpen"
  >
    <div class="picker-body">
      <div
        :class="['picker-root-item', { active: selectedId === 0 }]"
        @click="selectedId = 0"
      >
        <el-icon><FolderOpen /></el-icon>
        <span>根目录</span>
      </div>
      <el-tree
        ref="treeRef"
        :data="treeData"
        :props="{ label: 'name', children: 'children', isLeaf: 'leaf' }"
        node-key="id"
        lazy
        :load="loadNode"
        highlight-current
        :expand-on-click-node="false"
        class="picker-tree"
        @node-click="(data) => (selectedId = data.id)"
      >
        <template #default="{ data }">
          <div :class="['picker-tree-node', { active: selectedId === data.id }]">
            <el-icon><Folder /></el-icon>
            <span class="picker-node-label" :title="data.name">{{ data.name }}</span>
          </div>
        </template>
      </el-tree>
    </div>
    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="confirming" :disabled="selectedId === null" @click="handleConfirm">
          确定
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { Folder, FolderOpen } from 'lucide-vue-next'
import { listSubFoldersApi } from '@/api/folder'

const props = defineProps({
  spaceId: { type: [Number, String], required: true },
  title: { type: String, default: '选择目标文件夹' },
  // 需要禁止选中的文件夹 id 集合 (移动文件夹时排除自身与子孙由后端兜底，前端仅排除自身)
  disabledIds: { type: Array, default: () => [] }
})

const emit = defineEmits(['confirm'])

const visible = defineModel({ type: Boolean, default: false })

const treeRef = ref(null)
const treeData = ref([])
const selectedId = ref(0)
const confirming = ref(false)

function handleOpen() {
  selectedId.value = 0
  treeData.value = []
}

async function loadNode(node, resolve) {
  const parentId = node.level === 0 ? 0 : node.data.id
  try {
    const list = await listSubFoldersApi(props.spaceId, parentId)
    const nodes = list
      .filter((f) => !props.disabledIds.includes(f.id))
      .map((f) => ({ id: f.id, name: f.name, leaf: false }))
    resolve(nodes)
  } catch (err) {
    resolve([])
  }
}

async function handleConfirm() {
  confirming.value = true
  try {
    await Promise.resolve(emit('confirm', selectedId.value))
    visible.value = false
  } finally {
    confirming.value = false
  }
}
</script>

<style scoped>
.picker-body {
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 8px;
}

.picker-root-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 0.875rem;
  color: var(--app-text-2);
  cursor: pointer;
  margin-bottom: 4px;
}

.picker-root-item:hover {
  background-color: var(--app-hover);
}

.picker-root-item.active {
  background-color: var(--app-accent-weak);
  color: var(--app-accent);
  font-weight: 600;
}

.picker-tree {
  background: transparent;
}

.picker-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.875rem;
  min-width: 0;
  overflow: hidden;
}

.picker-tree-node.active {
  color: var(--app-accent);
  font-weight: 600;
}

.picker-node-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
