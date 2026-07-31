<template>
  <div class="activity-page">
    <div class="activity-container">
      <div class="page-head anim-item" style="--delay: 0">
        <h1 class="page-title">团队动态</h1>
        <p class="page-sub">你所在空间的最近操作记录</p>
      </div>

      <div v-if="spaces.length > 1" class="space-tabs anim-item" style="--delay: 1">
        <button
          type="button"
          :class="['space-tab', { active: activeSpaceId === null }]"
          @click="switchSpace(null)"
        >
          全部空间
        </button>
        <button
          v-for="s in spaces"
          :key="s.id"
          type="button"
          :class="['space-tab', { active: activeSpaceId === s.id }]"
          @click="switchSpace(s.id)"
        >
          <span class="tab-dot" :style="{ background: spaceIconPalette(s.id).text }"></span>
          {{ s.name }}
        </button>
      </div>

      <div v-loading="loading" class="activity-panel anim-item stagger-rows" style="--delay: 2">
        <EmptyState
          v-if="!loading && activities.length === 0"
          :icon="UsersRound"
          title="还没有团队动态"
          description="空间里的操作会出现在这里"
        />

        <div v-for="act in activities" :key="act.id" class="activity-row">
          <el-avatar
            v-if="!act.avatar"
            :size="34"
            class="row-avatar"
            :style="{ background: avatarColor(act.username) }"
          >
            {{ (act.username || 'U').charAt(0).toUpperCase() }}
          </el-avatar>
          <el-avatar v-else :size="34" class="row-avatar" :src="act.avatar" />

          <div class="row-main">
            <p class="row-text">
              <span class="row-user">{{ act.username }}</span>
              {{ activityVerb(act) }}
              <template v-if="activityName(act)">
                <a
                  v-if="canOpenActivityDocument(act)"
                  class="row-doc"
                  @click.prevent="openActivityDoc(act)"
                >{{ truncateText(activityName(act), 40) }}</a>
                <span
                  v-else-if="activityMeta(act).style === 'doc' || activityMeta(act).style === 'strong'"
                  class="row-strong"
                >{{ truncateText(activityName(act), 40) }}</span>
                <span
                  v-else-if="activityMeta(act).style === 'quote'"
                  class="row-quote"
                >“{{ truncateText(activityName(act), 60) }}”</span>
                <template v-if="activityMeta(act).suffix">{{ activityMeta(act).suffix }}</template>
              </template>
            </p>
            <span class="row-space">{{ act.spaceName }}</span>
          </div>

          <span class="row-time">{{ formatRelativeTime(act.createdAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { UsersRound } from 'lucide-vue-next'
import { getActivitiesApi } from '@/api/activity'
import EmptyState from '@/components/EmptyState.vue'
import { useSpacesStore } from '@/stores'
import { formatRelativeTime } from '@/utils/format'
import { avatarColor } from '@/utils/userColors'
import { spaceIconPalette } from '@/utils/spaceColors'
import { activityMeta, activityName, activityVerb, canOpenActivityDocument, truncateText } from '@/utils/activityText'
import { useDocumentNavigation } from '@/composables/useDocumentNavigation'

const { openDocument } = useDocumentNavigation()
const spacesStore = useSpacesStore()
const { spaces } = storeToRefs(spacesStore)

const loading = ref(true)
const activities = ref([])
const activeSpaceId = ref(null)

onMounted(() => {
  spacesStore.refresh()
  loadActivities()
})

async function loadActivities() {
  loading.value = true
  try {
    activities.value = await getActivitiesApi(50, activeSpaceId.value ?? undefined)
  } catch (err) {
    activities.value = []
  } finally {
    loading.value = false
  }
}

function switchSpace(spaceId) {
  if (activeSpaceId.value === spaceId) return
  activeSpaceId.value = spaceId
  loadActivities()
}

function openActivityDoc(act) {
  if (!canOpenActivityDocument(act) || !act.spaceId) return
  openDocument({
    spaceId: act.spaceId,
    documentId: act.resourceId
  })
}
</script>

<style scoped>
.activity-page {
  flex: 1;
  overflow-y: auto;
  padding: 2rem 2.5rem 3rem;
}

.activity-container {
  max-width: 880px;
  margin: 0 auto;
}

.page-head {
  margin-bottom: 1.5rem;
}

.page-title {
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--app-text);
  margin: 0 0 0.3rem;
}

.page-sub {
  font-size: 0.85rem;
  color: var(--app-text-faint);
  margin: 0;
}

.space-tabs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 1rem;
}

.space-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--app-border);
  background: var(--app-panel);
  color: var(--app-text-muted);
  font-size: 0.82rem;
  font-weight: 500;
  padding: 5px 13px;
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--dur-fast) var(--ease-standard);
}

.space-tab:hover {
  border-color: var(--app-text-faint);
  color: var(--app-text);
}

.space-tab.active {
  background: var(--app-accent);
  border-color: var(--app-accent);
  color: #fff;
}

.tab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.space-tab.active .tab-dot {
  background: rgba(255, 255, 255, 0.85) !important;
}

.activity-panel {
  background: var(--app-panel);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
  min-height: 200px;
}

.activity-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 13px 18px;
}

.activity-row + .activity-row {
  border-top: 1px solid var(--app-border-soft);
}

.row-avatar {
  flex-shrink: 0;
  color: #fff;
  font-size: 0.82rem;
  font-weight: 600;
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row-text {
  margin: 0;
  font-size: 0.88rem;
  line-height: 1.55;
  color: var(--app-text-muted);
  word-break: break-word;
}

.row-user {
  font-weight: 600;
  color: var(--app-text);
}

.row-doc {
  color: var(--app-accent);
  cursor: pointer;
}

.row-doc:hover {
  text-decoration: underline;
}

.row-strong {
  font-weight: 600;
  color: var(--app-text);
}

.row-quote {
  color: var(--app-text-muted);
}

.row-space {
  font-size: 0.75rem;
  color: var(--app-text-faint);
}

.row-time {
  flex-shrink: 0;
  font-size: 0.78rem;
  color: var(--app-text-faint);
  padding-top: 2px;
}

@media (max-width: 768px) {
  .activity-page { padding: 1.1rem 1rem 2rem; }
  .row-time { display: none; }
}
</style>
