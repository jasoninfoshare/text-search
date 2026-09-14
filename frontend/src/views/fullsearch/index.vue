<template>
  <div class="app-container-new" :class="{ 'is-stuck': searchStuck }" style="padding-bottom: 0px; background: #f5f6f8">
    <div class="fs-panel">
    <div class="fs-search-wrap">
      <div class="g-search" :class="{ 'is-focus': sugVisible, 'ai-enabled': aiExpand }">
      <span class="g-search-glow" aria-hidden="true"></span>
      <span class="g-search-ring" aria-hidden="true"></span>
      <span class="g-search-fill" aria-hidden="true"></span>
      <input
        ref="searchInput"
        v-model="queryParams.keyword"
        class="g-search-input"
        type="text"
        placeholder="输入关键词，多关键词以逗号分隔"
        @focus="onSearchFocus"
        @input="onSearchInput"
        @keydown.down.prevent="moveSug(1)"
        @keydown.up.prevent="moveSug(-1)"
        @keydown.enter="onSearchEnter"
        @keydown.esc="closeSug"
        @blur="onSearchBlur"
      />
      <i v-show="queryParams.keyword" class="el-icon-circle-close g-search-clear" @mousedown.prevent="clearKeyword"></i>
      <button
        class="g-search-ai-toggle"
        :class="{ active: aiExpand }"
        :title="aiExpand ? 'AI 扩词已开启，点击关闭' : '点击开启 AI 扩词'"
        @click="toggleAiExpand"
      >
        <span class="ai-dot"></span>
        <span class="ai-label">AI 扩词</span>
      </button>
      <button class="g-search-btn" @click="doSearch">
        <i class="el-icon-search"></i>
        <span>搜索</span>
      </button>
      <ul v-show="sugVisible && suggestions.length" class="g-suggest">
        <li
          v-for="(s, i) in suggestions"
          :key="i"
          :class="{ active: i === sugIndex }"
          @mousedown.prevent="selectSug(s)"
          @mouseenter="sugIndex = i"
        >
          <i class="g-suggest-lead" :class="s.type === 'hot' ? 'el-icon-data-analysis' : 'el-icon-time'"></i>
          <span class="g-suggest-text">{{ s.text }}</span>
          <span v-if="s.type === 'hot'" class="g-suggest-tag hot">热门</span>
          <i v-if="s.type === 'history'" class="el-icon-close g-suggest-del" title="删除该历史" @mousedown.prevent.stop="delHistory(s.text)"></i>
        </li>
      </ul>
    </div>
    </div>
    <div class="fs-filter">
      <!-- 类别标签 + 筛选工具：Google 式一行布局（分类在左、筛选在右） -->
      <div class="fs-tabs-bar">
        <div class="fs-tabs" ref="mainTabs">
          <span class="fs-tab-bg" :style="mainTabBgStyle"></span>
          <span
            v-for="c in categories"
            :key="c.value"
            class="fs-tab"
            :class="{ active: queryParams.type === c.value }"
            @click="selectCategory(c.value)"
          >{{ c.label }}</span>
        </div>
        <div class="fs-filter-right">
          <!-- 检索范围：默认全文 -->
          <el-select size="small" style="width:106px" v-model="queryParams.method" @change="setMethod" placeholder="全文">
            <el-option label="全文" value="1"></el-option>
            <el-option label="仅标题" value="2"></el-option>
          </el-select>
          <!-- 时间筛选 -->
          <el-select class="fs-sel-date" size="small" style="width:106px" v-model="datePreset" @change="onDatePreset" placeholder="时间不限">
            <el-option label="时间不限" value=""></el-option>
            <el-option label="最近一周" value="7"></el-option>
            <el-option label="最近一月" value="30"></el-option>
            <el-option label="最近半年" value="180"></el-option>
            <el-option label="最近一年" value="365"></el-option>
            <el-option label="自定义…" value="custom"></el-option>
          </el-select>
          <!-- 自定义日期段：紧跟时间筛选，时间类控件连在一起 -->
          <el-date-picker
            class="fs-sel-range"
            v-if="datePreset === 'custom'"
            v-model="times"
            type="daterange"
            align="right"
            style="width:240px"
            size="small"
            value-format="yyyy-MM-dd"
            unlink-panels
            range-separator="至"
            @change="doSearch()"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :picker-options="pickerOptions">
          </el-date-picker>
          <!-- 部门筛选：选中值可能很长，悬停显示完整名称；固定在筛选组最后 -->
          <span :title="deptSelectedLabel" style="display:inline-flex">
            <el-select class="fs-sel-dept" size="small" style="width:320px" v-model="selectedDept" @change="onDeptChange" placeholder="全部部门" clearable>
              <el-option label="全部部门" value=""></el-option>
              <el-option v-for="d in deptList" :key="d" :label="deptLabelOf(d)" :value="deptValueOf(d)"></el-option>
            </el-select>
          </span>
        </div>
      </div>
      <!-- 公文二级类型 + 结果数：同一行（左类型、右计数），非公文 tab 时计数自然居左 -->
      <div class="fs-subtabs-count-row">
        <transition name="subtabs-pop">
          <div class="fs-subtabs" v-if="queryParams.type === '99'" ref="subtabs">
            <span class="fs-subtabs-label">公文类型：</span>
            <span
              v-for="d in docTypes"
              :key="d.value"
              class="fs-subtab"
              :class="{ active: queryParams.docType === d.value }"
              @click="selectDocType(d.value)"
            >{{ d.label }}</span>
            <span class="fs-subtab-ink" :style="subtabInkStyle"></span>
          </div>
        </transition>
        <span class="fs-count">
          共找到约 <b>{{ realTotal }}</b> 个结果
          <span v-if="realTotal > total" class="fs-count-note">（已按时间精选前 {{ total }} 条）</span>
          <span v-if="multiTermsHint" class="fs-multi-hint">组合检索：{{ multiTermsHint }}（同时包含）</span>
          <span v-if="realTotal > 500" class="fs-many-hint">结果较多，建议加部门/时间筛选，或换个更精确的词</span>
          <span class="fs-scope-note" title="更早的历史文件在老 OA 系统，暂未收录">范围：2025-08 起</span>
        </span>
      </div>
    </div>
    </div>
    <div v-if="aiExpand && aiTerms.length" class="ai-suggest">
      <span class="ai-badge">AI</span>
      <span class="ai-suggest-label">为你联想相关词：</span>
      <span class="ai-chip" v-for="(w,i) in aiTerms" :key="i" @click="searchByTerm(w)">{{ w }}<i class="el-icon-close ai-chip-x" @click.stop="removeTerm(i)" title="移除该联想词"></i></span>
      <span class="ai-suggest-tip">（正文中 <i class="tip-red">红色</i> 为搜索词，<i class="tip-yellow">黄色</i> 为联想词）</span>
    </div>
    <div v-if="relatedSearches.length" class="related-searches">
      <span class="related-label">搜索该词的用户还搜了：</span>
      <span class="related-chip" v-for="(w,i) in relatedSearches" :key="i" @click="searchByTerm(w)">{{ w }}</span>
    </div>
    <div class="full-search-content" :class="{ 'is-reloading': loading && dataList.length }">
      <div class="full-search-content-all">
        <!-- 加载骨架屏：仅首次搜索（无旧结果）时显示；重新搜索保留旧列表防止页面塌高跳闪 -->
        <div v-if="loading && dataList.length === 0" class="skeleton-wrap">
          <div class="skeleton-card" v-for="n in 4" :key="'sk'+n">
            <div class="sk-line sk-title"></div>
            <div class="sk-line sk-text"></div>
            <div class="sk-line sk-text short"></div>
            <div class="sk-line sk-meta"></div>
          </div>
        </div>

        <!-- 结果卡片 -->
        <template v-else-if="dataList.length">
          <div class="result-card" v-for="(item,index) in dataList" :key="index" @click="toDetail(item)">
            <div class="result-title">
              <span class="result-title-text" v-html="hlContent(item.title)"></span>
            </div>
            <div class="result-frag" v-if="item.bestFragment">
              <div class="result-frag-con" v-html="hlContent(item.bestFragment.content)"></div>
              <div class="result-meta">
                <span v-if="item.bestFragment.title" class="meta-item">
                  <i :class="fileTypeIcon(item.bestFragment.title).cls" :style="{ color: fileTypeIcon(item.bestFragment.title).color }"></i>
                  <a v-if="item.bestFragment.url" href="javascript:void(0)" class="file-link" @click.stop="openTargetFile(item.bestFragment)">{{ item.bestFragment.title }}</a>
                  <span v-else>{{ item.bestFragment.title }}</span>
                </span>
                <span v-if="item.bestFragment.type" class="meta-item"><el-tag size="mini" :type="resultTypeTag(item.bestFragment.type, item.types)">{{ resultTypeFormatter(item.bestFragment.type, item.types) }}</el-tag></span>
                <span v-if="item.createTime" class="meta-item"><i class="el-icon-time"></i>{{ item.createTime.substring(0,10) }}</span>
                <span v-if="item.results.length > 1" class="meta-more">命中 {{ item.results.length - 1 }} 处</span>
                <span v-if="item.params && item.params.semanticOnly" class="semantic-badge" title="语义召回补充：内容与搜索词相关，但不含搜索词原文">语义相关</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 空状态 -->
        <div v-else class="empty-state">
          <!-- 初始空态（未输入关键词）：热门搜索 + 搜索历史 + 检索小技巧 -->
          <template v-if="!searchedKeyword">
            <i class="el-icon-search empty-icon"></i>
            <div class="empty-title">输入关键词，开始全文检索</div>

            <div v-if="hotWords.length" class="empty-section">
              <div class="empty-section-title"><i class="el-icon-data-analysis"></i>热门搜索</div>
              <div class="empty-chips">
                <span class="empty-chip hot" v-for="(w,i) in hotWords" :key="'hot'+i" @click="searchByTerm(w)">{{ w }}</span>
              </div>
            </div>

            <div v-if="searchHistory.length" class="empty-section">
              <div class="empty-section-title"><i class="el-icon-time"></i>搜索历史</div>
              <div class="empty-chips">
                <span class="empty-chip" v-for="(w,i) in searchHistory" :key="'his'+i" @click="searchByTerm(w)">
                  {{ w }}<i class="el-icon-close empty-chip-x" title="删除该历史" @click.stop="delHistory(w)"></i>
                </span>
              </div>
            </div>

            <div class="empty-tips">
              <div class="empty-tips-title">检索小技巧</div>
              <div class="empty-tip-item"><i class="el-icon-collection-tag"></i>多关键词用逗号分隔，如"借调,纪委"，检索同时包含这些词的内容</div>
              <div class="empty-tip-item"><i class="el-icon-magic-stick"></i>开启"AI 扩词"自动联想同义词（如 弱口令→弱密码、默认密码）；多词搜索时自动开启</div>
              <div class="empty-tip-item"><i class="el-icon-date"></i>可用右侧时间、部门筛选缩小范围；检索范围为 2025 年 8 月以来的公文和新闻公告</div>
            </div>
          </template>

          <!-- 无结果空态（已搜索） -->
          <template v-else>
            <i class="el-icon-search empty-icon"></i>
            <div class="empty-title">没找到与「{{ searchedKeyword }}」相关的内容</div>
            <div class="empty-tip" v-if="didYouMean">
              您是不是想搜：<a href="javascript:void(0)" class="did-you-mean" @click="searchByTerm(didYouMean)">{{ didYouMean }}</a>？
            </div>
            <!-- 精确检索零结果：引导一键开启 AI 扩词重搜 -->
            <div class="empty-tip" v-else-if="!aiExpand">
              精确检索未找到，试试
              <a href="javascript:void(0)" class="ai-expand-cta" @click="enableAiAndSearch">开启 AI 扩词</a>
              自动联想同义词再搜一次？
            </div>
            <div class="empty-tip" v-else-if="aiExpand && aiTerms.length">
              换个说法试试：
              <span class="ai-chip" v-for="(w,i) in aiTerms" :key="i" @click="searchByTerm(w)">{{ w }}<i class="el-icon-close ai-chip-x" @click.stop="removeTerm(i)" title="移除该联想词"></i></span>
            </div>
            <div class="empty-tip" v-else>换个关键词，或减少筛选条件再试试</div>
            <div class="empty-scope">说明：检索范围为 2025 年 8 月以来的公文和新闻公告；更早的历史文件在老 OA 系统，暂未收录。</div>
          </template>
        </div>
      </div>
      <div class="load-more">
        <div v-if="dataList.length > 0" class="load-more-status">
          共 {{ realTotal }} 条，已显示 {{ dataList.length }} 条
        </div>
        <div v-if="loadingMore" class="load-more-text"><i class="el-icon-loading"></i> 加载中...</div>
        <div v-else-if="noMore && dataList.length > 0" class="load-more-text">没有更多了</div>
        <div v-else-if="dataList.length > 0 && dataList.length < realTotal" class="load-more-text">向下滚动加载更多</div>
        <div v-if="dataList.length > 20" class="back-to-top" @click="scrollToTop">
          <i class="el-icon-top"></i> 回到顶部
        </div>
      </div>
    </div>

    <wps-show ref="wpsShow"/>
  </div>
</template>
<script>
import {listOaPublishContent} from "@/api/portal/OaPublishContent";
import {esQuerys, hotWords as fetchHotWords} from "@/api/fullSearch/fullSecrch";
import WpsShow from '@/components/WpsShow'
import Pagination from '@/components/Pagination'
import {attachmentView,downLoadByCode} from "@/api/system/attachment";

export default {
  components: {WpsShow, Pagination},
  data() {
    return {
      input: '',
      dataList: [],
      // 主导航滑动胶囊 / 二级类型滑动下划线的位置状态（由实际 tab 测量）
      mainTabBg: { left: 0, top: 0, width: 0, height: 0, ready: false },
      subtabInk: { left: 0, width: 0, ready: false },
      total: 0,
      realTotal: 0,
      datePreset: '',
      loading: false,
      loadingMore: false,
      noMore: false,
      searchStuck: false,
      scrollHandler: null,
      searchedKeyword: '',
      aiTerms: [],
      hlTerms: [],
      didYouMean: '',
      relatedSearches: [],
      deptList: [],
      selectedDept: '',
      sugVisible: false,
      sugIndex: -1,
      suggestions: [],
      searchHistory: [],
      hotWords: [],
      dictWords: [],
      aiExpand: false,
      categories: [
        { label: '全部', value: '' },
        { label: '公文', value: '99' },
        { label: '集团新闻', value: '1' },
        { label: '子公司动态', value: '2' },
        { label: '通知公告', value: '3' },
        { label: '信息公开', value: '5' },
      ],
      // 公文二级类型
      docTypes: [
        { label: '全部', value: '' },
        { label: '收文', value: '1' },
        { label: '发文', value: '2' },
        { label: '签报', value: '3' },
        { label: '会议纪要', value: '4' },
        { label: '部门函', value: '6' },
      ],
      queryParams: {
        method: '2',
        type: '',
        docType: '',
        sort: 'time',
        pageNum: 1,
        keyword: '',
        pageSize: 20,
        aiExpand: false,
        deptName: '',
      },
      pickerOptions: {
        shortcuts: [
          {
            text: '时间不限',
            onClick(picker) {
              picker.$emit('pick', []);
            }
          },
          {
            text: '一周内',
            onClick(picker) {
              const end = new Date();
              const start = new Date();
              start.setTime(start.getTime() - 3600 * 1000 * 24 * 7);
              picker.$emit('pick', [start, end]);
            }
          },
          {
            text: '一月内',
            onClick(picker) {
              const end = new Date();
              const start = new Date();
              start.setTime(start.getTime() - 3600 * 1000 * 24 * 30);
              picker.$emit('pick', [start, end]);
            }
          },
          {
            text: '一年内',
            onClick(picker) {
              const end = new Date();
              const start = new Date();
              start.setTime(start.getTime() - 3600 * 1000 * 24 * 365);
              picker.$emit('pick', [start, end]);
            }
          },
          {
            text: (new Date().getFullYear()-2)+'年',
            onClick(picker) {
              const start = new Date(new Date().getFullYear()-2, 0, 1);
              const end = new Date(new Date().getFullYear()-2, 11, 31);
              picker.$emit('pick', [start, end]);
            }
          },
          {
            text: (new Date().getFullYear()-1)+'年',
            onClick(picker) {
              const start = new Date(new Date().getFullYear()-1, 0, 1);
              const end = new Date(new Date().getFullYear()-1, 11, 31);
              picker.$emit('pick', [start, end]);
            }
          },
          {
            text: new Date().getFullYear()+'年',
            onClick(picker) {
              const start = new Date(new Date().getFullYear(), 0, 1);
              const end = new Date(new Date().getFullYear(), 11, 31);
              picker.$emit('pick', [start, end]);
            }
          }
        ]
      },
      times: ""
    }
  },
  created() {
    localStorage.removeItem("full_serach_query_num")
    this.loadHistory();
    this.loadHotWords();
    this.loadDictWords();
    this.init();
  },
  mounted() {
    this.$nextTick(() => {
      this.bindScroll();
      this.updateMainTabBg();
      this.updateSubtabInk();
    });
    // 窗口尺寸变化时重新测量指示器位置
    this._tabResizeHandler = () => { this.updateMainTabBg(); this.updateSubtabInk(); };
    window.addEventListener('resize', this._tabResizeHandler, { passive: true });
    // 滚动触发正常态↔收缩态切换（tab 压缩变形），需要重新测量滑动胶囊，否则旧尺寸胶囊会压到相邻 tab
    this._tabScrollHandler = () => {
      if (this._tabScrollRaf) return;
      this._tabScrollRaf = requestAnimationFrame(() => {
        this._tabScrollRaf = null;
        this.updateMainTabBg();
        this.updateSubtabInk();
      });
    };
    window.addEventListener('scroll', this._tabScrollHandler, { passive: true });
  },
  beforeDestroy() {
    if (this._tabResizeHandler) window.removeEventListener('resize', this._tabResizeHandler);
    if (this._tabScrollHandler) {
      window.removeEventListener('scroll', this._tabScrollHandler);
      if (this._tabScrollRaf) cancelAnimationFrame(this._tabScrollRaf);
    }
    if (this._stuckMeasureTimer) clearTimeout(this._stuckMeasureTimer);
  },
  watch: {
    // 主导航切换：搜索的同时滑动胶囊
    'queryParams.type'() {
      this.updateMainTabBg();
      this.updateSubtabInk();
    },
    // 正常态↔收缩态切换：tab 压缩是 .28s CSS 过渡，立即量会拿到未压缩的旧尺寸（胶囊过宽被相邻 tab 切角），
    // 延迟到过渡结束后再重新测量
    searchStuck() {
      clearTimeout(this._stuckMeasureTimer);
      this._stuckMeasureTimer = setTimeout(() => {
        this.updateMainTabBg();
        this.updateSubtabInk();
      }, 340);
    },
    // 二级类型切换：滑动下划线
    'queryParams.docType'() {
      this.updateSubtabInk();
    },
    // num 变化时重新检索
    '$route.query.num'(num) {
      if (!num) return;
      let nums = localStorage.getItem("full_serach_query_num");
      let numArray = nums ? JSON.parse(nums) : [];
      if (!Array.isArray(numArray)) numArray = [];
      if (!numArray.includes(num)) {
        this.init();
      }
    },
    // params 中关键词变化时重新检索（顶栏搜索）
    '$route.params.keyword'() {
      this.init();
    }
  },
  activated() {
    // 激活
    this.$nextTick(() => {
      this.bindScroll();
    });
    let nums =  localStorage.getItem("full_serach_query_num");
    let numArray = nums ? JSON.parse(nums) : [];
    // 确保是数组
    if (!Array.isArray(numArray)) {
      numArray = [];
    }
    //路由
    if (this.$route.query.num && !numArray.includes(this.$route.query.num)) {
      this.resetParams()
      this.init()
    }
  },
  beforeDestroy() {
    this.unbindScroll();
    let startTime = ''
    let endTime  = ''
    if (this.times && this.times.length  === 2) {
      startTime = this.times[0]
      endTime = this.times[1]
    }
    let obj = {
      ...this.queryParams,
      startTime,
      endTime
    }
    localStorage.setItem('full_serach_query_params', JSON.stringify(obj));
  },
  computed: {
    // 主导航滑动胶囊样式：transform 位移 + 宽高，过渡由 CSS 负责
    mainTabBgStyle() {
      const b = this.mainTabBg;
      return {
        transform: `translate(${b.left}px, ${b.top}px)`,
        width: b.width + 'px',
        height: b.height + 'px',
        opacity: b.ready ? 1 : 0
      };
    },
    // 二级类型滑动下划线样式
    subtabInkStyle() {
      const k = this.subtabInk;
      return {
        transform: `translateX(${k.left}px)`,
        width: k.width + 'px',
        opacity: k.ready ? 1 : 0
      };
    },
    // 多词组合提示：逗号/空格/顿号/分号分隔的多词查询时，告知用户按"同时包含"检索
    multiTermsHint() {
      const kw = (this.searchedKeyword || '').trim();
      const parts = kw.split(/[\s　,，、;；]+/).filter(Boolean);
      return parts.length > 1 ? parts.join(' + ') : '';
    },
    // 部门下拉当前选中项的完整展示名（悬停 tooltip 用；列表里没有时兜底回原值）
    deptSelectedLabel() {
      if (!this.selectedDept) return '';
      const cur = this.deptList.find(d => this.deptValueOf(d) === this.selectedDept);
      return cur ? this.deptLabelOf(cur) : this.selectedDept;
    }
  },
  methods: {
    // 测量并移动主导航滑动胶囊到当前激活 tab
    updateMainTabBg() {
      this.$nextTick(() => {
        const wrap = this.$refs.mainTabs;
        if (!wrap) return;
        const active = wrap.querySelector('.fs-tab.active');
        if (!active) { this.mainTabBg.ready = false; return; }
        this.mainTabBg = {
          left: active.offsetLeft,
          top: active.offsetTop,
          width: active.offsetWidth,
          height: active.offsetHeight,
          ready: true
        };
      });
    },
    // 测量并移动二级类型滑动下划线到当前激活项
    updateSubtabInk() {
      this.$nextTick(() => {
        const wrap = this.$refs.subtabs;
        if (!wrap) { this.subtabInk.ready = false; return; }
        const active = wrap.querySelector('.fs-subtab.active');
        if (!active) { this.subtabInk.ready = false; return; }
        this.subtabInk = {
          left: active.offsetLeft,
          width: active.offsetWidth,
          ready: true
        };
      });
    },
    init(){
      let nums =  localStorage.getItem("full_serach_query_num");
      let numArray = nums ? JSON.parse(nums) : [];
        // 确保是数组
      if (!Array.isArray(numArray)) {
        numArray = [];
      }
      // 添加新的 num 值（如果不存在的话）
      if (this.$route.query.num && !numArray.includes(this.$route.query.num)) {
        numArray.push(this.$route.query.num);
      }
      // 打开记录
      localStorage.setItem('full_serach_query_num',JSON.stringify(numArray))
      this.resetParams();
      this.getList();
    },
    resetParams() {
      // 路由params 优先级高于缓存数据
      let stroeParams = localStorage.getItem('full_serach_query_params')
      let template ={}
      try {
        template = this.$route.params && Object.keys(this.$route.params).length > 0 ? this.$route.params : (stroeParams ? JSON.parse(stroeParams) : {});
      } catch (e) {
        console.error("ativated Failed to parse stroeParams", e);
        template = {};
      }
      const {keyword, method, type, docType, startTime, endTime,pageNum,pageSize, sort} = template;
      // 设置 queryParams，保留当前 AI 扩词开关状态
      const currentAiExpand = this.queryParams.aiExpand;
      this.queryParams = {
        keyword,
        method: method || '2',
        type,
        docType: docType || '',
        sort: sort || 'time',
        pageNum: pageNum || 1,
        pageSize: pageSize || 20,
        aiExpand: currentAiExpand,
      };
      if(!this.queryParams.type){
        this.queryParams.type = ''
      }
      // 设置 times
      if (startTime) {
        this.times = [startTime, endTime || '']; // 如果 endTime 不存在，可以设置为空字符串或其他默认值
      } else {
        this.times = '';
      }

      localStorage.setItem('full_serach_query_params', JSON.stringify(template));
    },
    // 获取任意年份的第一天
    getFirstDayOfYear(year) {
      return new Date(year, 0, 1);
    },
    // 获取任意年份的最后一天
    getLastDayOfYear(year) {
      return new Date(year, 11, 31);
    },
    /** 查询参数列表 */
    getList(append = false) {
      // 排序固定按时间（页面上不提供相关度入口）
      this.queryParams.sort = 'time';
      // 无关键词不查询：列表保持空态，只展示"输入关键词开始全文检索"
      if (!append && !(this.queryParams.keyword || '').trim()) {
        this.dataList = [];
        this.total = 0;
        this.realTotal = 0;
        this.noMore = true;
        this.searchedKeyword = '';
        this.aiTerms = [];
        this.didYouMean = '';
        this.deptList = [];
        this.loading = false;
        this.loadingMore = false;
        return;
      }
      if (!append) {
        // 重新搜索不清空旧列表：保持页面高度稳定，否则骨架屏塌高会让浏览器把滚动位置瞬间钳回顶部（跳闪）
        this.noMore = false;
      }
      const isLoadingMore = append;
      if (isLoadingMore) {
        this.loadingMore = true;
      } else {
        this.loading = true;
      }
      let rolesList = this.$store.state.user.rolesList
      if (rolesList) {
        let rolesQuery = []
        for (let i in rolesList) {
          rolesQuery.push(rolesList[i].roleKey)
        }
        this.queryParams.roles = rolesQuery.join(',')
      }
      this.queryParams.deptId = this.$store.state.user.deptId
      this.queryParams.tenantId = this.$store.state.user.tenantId
      this.queryParams.userId = this.$store.state.user.userId

      if (this.times) {
        this.queryParams.startTime = this.times[0]
        this.queryParams.endTime = this.times[1]
      } else {
        this.queryParams.startTime = ''
        this.queryParams.endTime = ''
      }
      if(this.queryParams.type != '99'){
        this.queryParams.docType = ''
      }
      if (!append) {
        this.searchedKeyword = (this.queryParams.keyword || '').trim();
      }
      // 第1页先清掉上次的 AI 联想词
      if (this.queryParams.pageNum === 1 && !append) { this.aiTerms = []; this.didYouMean = ''; }
      esQuerys(this.queryParams).then(response => {
        // 先解析扩词，后续过滤/高亮/选片段都依赖它
        const msg = response.msg || '';
        const parts = msg.split('||');
        const clean = arr => (arr || '').split(',').map(s => s.trim()).filter(s => s && s !== '查询成功' && s !== '操作成功' && s !== 'success');
        if (!append) {
          this.aiTerms = parts.length > 1 ? clean(parts[1]) : [];
          this.hlTerms = parts.length > 2 ? clean(parts[2]) : this.aiTerms.slice();
          this.didYouMean = parts.length > 3 ? (parts[3] || '').trim() : '';
          this.deptList = parts.length > 4 ? clean(parts[4]) : [];
        }

        const rows = response.rows || [];
        for (let row of rows) {
          try {
            row.results = this.dedupFragments(JSON.parse(row.content));
            row.bestFragment = this.bestFragment(row);
          } catch (e) {
            row.results = [];
            row.bestFragment = null;
          }
        }
        // 精确命中的结果排前面，相关结果排后面（仅相关度排序时）
        if (this.searchedKeyword && this.queryParams.sort !== 'time') {
          rows.sort((a, b) => this.rowMatchScore(b) - this.rowMatchScore(a));
        }
        if (append) {
          this.dataList = this.dataList.concat(rows);
        } else {
          this.dataList = rows;
        }
        this.total = this.dataList.length;
        const rt = parseInt(parts[0], 10);
        this.realTotal = isNaN(rt) ? this.dataList.length : rt;
        this.noMore = this.dataList.length >= this.realTotal || rows.length === 0;
        this.loading = false;
        this.loadingMore = false;
        if (!append) {
          // 新结果就位后平滑滚回顶部：配合保留旧列表，回顶是动画而不是瞬间跳闪
          this.$nextTick(() => this.scrollToTop());
        }
      }).catch(err => {
        console.error('全文检索失败', err);
        if (!append) {
          this.dataList = [];
          this.total = 0;
          this.realTotal = 0;
          this.aiTerms = []; this.didYouMean = '';   // 出错清掉联想词/纠错
        }
        this.loading = false;
        this.loadingMore = false;
      });
    },
    // 分页器翻页/改页大小
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page;
      this.queryParams.pageSize = limit;
      this.getList();
    },
    // 回到顶部
    scrollToTop() {
      const container = document.querySelector('.full-search-content');
      if (container) {
        container.scrollTo({ top: 0, behavior: 'smooth' });
      } else {
        window.scrollTo({ top: 0, behavior: 'smooth' });
      }
    },
    // 过滤掉在当前结果中没有实际匹配的 AI 联想词
    filterAiTerms(rows) {
      if (!this.aiTerms.length || !rows.length) return;
      const textSet = new Set();
      for (const row of rows) {
        if (row.title) textSet.add(String(row.title).toLowerCase());
        if (row.bestFragment && row.bestFragment.content) {
          textSet.add(String(row.bestFragment.content).toLowerCase());
        }
        if (row.results && Array.isArray(row.results)) {
          for (const frag of row.results) {
            if (frag.content) textSet.add(String(frag.content).toLowerCase());
            if (frag.title) textSet.add(String(frag.title).toLowerCase());
          }
        }
      }
      this.aiTerms = this.aiTerms.filter(term => {
        const t = String(term).toLowerCase().trim();
        if (!t) return false;
        for (const text of textSet) {
          if (text.includes(t)) return true;
        }
        return false;
      });
    },
    // 加载下一页
    loadMore() {
      if (this.loading || this.loadingMore || this.noMore) return;
      if (this.dataList.length >= this.realTotal) {
        this.noMore = true;
        return;
      }
      this.queryParams.pageNum += 1;
      this.getList(true);
    },
    // 滚动监听
    bindScroll() {
      this.unbindScroll();
      const container = document.querySelector('.full-search-content') || window;
      this.scrollHandler = () => {
        let scrollTop, scrollHeight, clientHeight;
        if (container === window) {
          scrollTop = window.pageYOffset || document.documentElement.scrollTop;
          scrollHeight = document.documentElement.scrollHeight;
          clientHeight = document.documentElement.clientHeight;
        } else {
          scrollTop = container.scrollTop;
          scrollHeight = container.scrollHeight;
          clientHeight = container.clientHeight;
        }
        // 滚动压缩：超过 220px 才收缩，回到 120px 以内才展开（迟滞区间避免边界抖动）
        if (!this.searchStuck && scrollTop > 220) {
          this.searchStuck = true;
        } else if (this.searchStuck && scrollTop < 120) {
          this.searchStuck = false;
        }
        if (this.loading || this.loadingMore || this.noMore) return;
        if (scrollTop + clientHeight >= scrollHeight - 80) {
          this.loadMore();
        }
      };
      container.addEventListener('scroll', this.scrollHandler);
    },
    unbindScroll() {
      if (this.scrollHandler) {
        const container = document.querySelector('.full-search-content') || window;
        container.removeEventListener('scroll', this.scrollHandler);
        this.scrollHandler = null;
      }
    },
    // 原词标红（无背景），AI 相关词标黄字（无背景）
    hlContent(html) {
      let s = String(html || '');
      const list = [];
      const kw = (this.searchedKeyword || '').trim();
      if (kw.length >= 1) kw.split(/[\s　,，、;；]+/).filter(Boolean).forEach(tok => list.push([tok, '#f56c6c', 'transparent', true])); // 原词标红（多词组合按分隔符拆开逐词标）
      (this.hlTerms || []).filter(t => t && String(t).length >= 2).forEach(t => list.push([String(t), '#f0ad4e', 'transparent', false])); // 扩展词(AI+本地词典)标黄字
      for (const [t, color, bgColor, isOriginal] of list) {
        const esc = String(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const extra = isOriginal ? ';border-radius:2px;padding:0 2px' : '';
        try { s = s.replace(new RegExp('(' + esc + ')(?![^<]*>)', 'g'), '<span style="color:' + color + ';background:' + bgColor + ';font-weight:bold' + extra + '">$1</span>'); } catch (e) {}
      }
      return s;
    },
    // 同一公文 PDF/DOCX 两版正文按内容去重
    dedupFragments(list) {
      if (!Array.isArray(list)) return [];
      const seen = new Set();
      const out = [];
      for (const f of list) {
        const raw = f && f.content ? String(f.content) : '';
        const key = raw.replace(/<[^>]+>/g, '').replace(/[\s　]+/g, '').replace(/[^一-龥a-zA-Z0-9]/g, '').slice(0, 60);
        const k = key || (f && f.title) || JSON.stringify(f);
        if (seen.has(k)) continue;
        seen.add(k);
        out.push(f);
      }
      return out;
    },
    // 发起新检索，重置到第一页
    doSearch() {
      this.saveHistory(this.queryParams.keyword);
      this.sugVisible = false;
      this.queryParams.pageNum = 1;
      // 多词组合（逗号/空格/顿号/分号分隔）自动开启 AI 扩词，提高召回
      const kw = (this.queryParams.keyword || '').trim();
      if (/[\s　,，、;；]+/.test(kw) && !this.aiExpand) {
        this.aiExpand = true;
        this.queryParams.aiExpand = true;
      }
      this.getList();
      this.loadRelatedSearches();
      this.loadAiExpandAsync();
    },
    // 点击类别标签筛选
    selectCategory(v) {
      this.queryParams.type = v;
      if (v !== '99') this.queryParams.docType = '';
      this.doSearch();
    },
    // 点击公文二级类型筛选
    selectDocType(v) {
      this.queryParams.docType = v;
      this.doSearch();
    },
    // 切换检索范围：全文 / 标题
    setMethod(m) {
      // 注意：el-select 是 v-model + @change 双绑，v-model 已先把 method 同步成新值，
      // 这里不能再比较"新旧值是否相等"——比较永远是相等，会导致切换不触发搜索
      this.queryParams.method = m;
      this.doSearch();
    },
    // 切换 AI 扩词开关
    toggleAiExpand() {
      this.aiExpand = !this.aiExpand;
      this.queryParams.aiExpand = this.aiExpand;
      // 不弹全局提示：按钮点亮/熄灭即是状态反馈，开关后立即重搜，结果变化自然可见
      if (this.queryParams.keyword) {
        this.doSearch();
      }
    },
    // 零结果时一键开启 AI 扩词并重搜（空结果页 CTA）
    enableAiAndSearch() {
      if (this.aiExpand) return;
      this.aiExpand = true;
      this.queryParams.aiExpand = true;
      this.doSearch();
    },
    // 部门筛选
    // 部门下拉条目格式"展示名#原值"（裸名补归口标注）：展示与筛选值分离
    deptLabelOf(d) {
      const i = (d || '').indexOf('#');
      return i > 0 ? d.substring(0, i) : d;
    },
    deptValueOf(d) {
      const i = (d || '').indexOf('#');
      return i > 0 ? d.substring(i + 1) : d;
    },
    onDeptChange(v) {
      this.queryParams.deptName = v || '';      this.doSearch();
    },
    // 时间预设，算出起止日期
    onDatePreset(v) {
      if (v === 'custom') {
        this.times = '';
        return; // 等日历 change 再搜
      }
      if (!v) {
        this.times = '';
      } else {
        const days = parseInt(v, 10);
        const end = new Date();
        const start = new Date();
        start.setTime(start.getTime() - days * 24 * 3600 * 1000);
        const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        this.times = [fmt(start), fmt(end)];
      }
      this.doSearch();
    },
    // 搜索建议：历史 + 前缀匹配
    loadHistory() {
      try {
        const h = JSON.parse(localStorage.getItem('fs_search_history') || '[]');
        this.searchHistory = Array.isArray(h) ? h : [];
      } catch (e) {
        this.searchHistory = [];
      }
    },
    loadHotWords() {
      fetchHotWords({ size: 10, days: 90 }).then(res => {
        const arr = res && res.data ? res.data : [];
        this.hotWords = Array.isArray(arr) ? arr : [];
      }).catch(() => {});
    },
    loadDictWords() {
      // 走 API 前缀（/xny-api），与其他接口一致；直接 fetch('/es/...') 不带前缀会被 nginx 当静态文件 404
      const base = process.env.VUE_APP_BASE_API || '';
      fetch(base + '/es/dictWords').then(res => res.json()).then(res => {
        const arr = res && res.data ? res.data : [];
        this.dictWords = Array.isArray(arr) ? arr : [];
      }).catch(() => {});
    },
    saveHistory(kw) {
      kw = (kw || '').trim();
      if (!kw) return;
      let h = this.searchHistory.filter(x => x !== kw);
      h.unshift(kw);
      this.searchHistory = h.slice(0, 10);
      localStorage.setItem('fs_search_history', JSON.stringify(this.searchHistory));
    },
    loadRelatedSearches() {
      const kw = (this.queryParams.keyword || '').trim();
      if (!kw) { this.relatedSearches = []; return; }
      const base = process.env.VUE_APP_BASE_API || '';
      fetch(base + '/es/relatedSearches?keyword=' + encodeURIComponent(kw)).then(res => res.json()).then(res => {
        const arr = res && res.data ? res.data : [];
        this.relatedSearches = Array.isArray(arr) ? arr : [];
      }).catch(() => { this.relatedSearches = []; });
    },
    // 异步加载 AI 扩展词，追加到 aiTerms
    loadAiExpandAsync() {
      if (!this.aiExpand) return;
      const kw = (this.queryParams.keyword || '').trim();
      if (!kw) return;
      const base = process.env.VUE_APP_BASE_API || '';
      fetch(base + '/es/aiExpandOnly?keyword=' + encodeURIComponent(kw)).then(res => res.json()).then(res => {
        const arr = res && res.data ? res.data : [];
        if (Array.isArray(arr) && arr.length) {
          const existing = new Set(this.aiTerms);
          arr.forEach(w => { if (!existing.has(w)) this.aiTerms.push(w); });
        }
      }).catch(() => {});
    },
    delHistory(kw) {
      this.searchHistory = this.searchHistory.filter(x => x !== kw);
      localStorage.setItem('fs_search_history', JSON.stringify(this.searchHistory));
      this.buildSuggestions();
    },
    buildSuggestions() {
      const kw = (this.queryParams.keyword || '').trim().toLowerCase();
      const seen = new Set();
      const list = [];
      const push = (text, type) => {
        const k = (text || '').toLowerCase();
        if (!text || seen.has(k)) return;
        seen.add(k);
        list.push({ text, type });
      };
      const match = t => t.toLowerCase().includes(kw) && t.toLowerCase() !== kw;
      if (!kw) {
        // 空输入：先历史，再热门，再词典
        this.searchHistory.forEach(t => push(t, 'history'));
        this.hotWords.forEach(t => push(t, 'hot'));
        this.dictWords.forEach(t => push(t, 'dict'));
      } else {
        // 有输入：历史、热门、词典各做包含匹配
        this.searchHistory.filter(match).forEach(t => push(t, 'history'));
        this.hotWords.filter(match).forEach(t => push(t, 'hot'));
        this.dictWords.filter(match).forEach(t => push(t, 'dict'));
      }
      this.suggestions = list.slice(0, 8);
      this.sugIndex = -1;
    },
    onSearchFocus() {
      this.buildSuggestions();
      this.sugVisible = true;
    },
    onSearchInput() {
      this.buildSuggestions();
      this.sugVisible = true;
    },
    onSearchBlur() {
      this.sugVisible = false;
    },
    closeSug() {
      this.sugVisible = false;
    },
    moveSug(dir) {
      if (!this.suggestions.length) this.buildSuggestions();
      if (!this.suggestions.length) return;
      this.sugVisible = true;
      const n = this.suggestions.length;
      this.sugIndex = this.sugIndex < 0
        ? (dir > 0 ? 0 : n - 1)
        : (this.sugIndex + dir + n) % n;
    },
    onSearchEnter() {
      if (this.sugVisible && this.sugIndex >= 0 && this.suggestions[this.sugIndex]) {
        this.selectSug(this.suggestions[this.sugIndex]);
      } else {
        this.doSearch();
      }
    },
    selectSug(s) {
      this.queryParams.keyword = s.text;
      this.sugVisible = false;
      this.doSearch();
    },
    clearKeyword() {
      this.queryParams.keyword = '';
      this.buildSuggestions();
      this.sugVisible = true;
      this.$nextTick(() => {
        this.$refs.searchInput && this.$refs.searchInput.focus();
      });
    },
    // 用该联想词重新检索
    searchByTerm(w) {
      if (!w) return;
      this.queryParams.keyword = w;
      this.queryParams.pageNum = 1;
      this.getList();
    },
    // 移除某个联想词
    removeTerm(i) {
      this.aiTerms.splice(i, 1);
    },
    // 命中打分：连写命中=2，分词全命中=1，都没有=0；AI 扩词命中额外 +0.2，最多 +0.6
    rowMatchScore(row) {
      const kw = (this.searchedKeyword || '').trim();
      if (!kw) return 1;
      const toks = kw.split(/[\s　]+/).filter(Boolean);
      const text = String(row.title || '') + ' ' + String(row.content || '') + ' ' + (row.results ? row.results.map(r => (r && r.content) ? r.content : '').join(' ') : '');

      // 标题命中排最前：标题含原词（去空格连写）直接给最高分，压过仅正文命中的长文档
      const titleText = String(row.title || '');
      let score = 0;
      if (kw && titleText.indexOf(toks.join('') || kw) >= 0) {
        score = 3; // 标题命中
      } else if (toks.length > 1 && text.indexOf(toks.join('')) >= 0) {
        score = 2; // 连写命中
      } else if (toks.every(t => text.indexOf(t) >= 0)) {
        score = 1; // 分词命中
      }

      // AI 扩词辅助加分，每个命中 +0.2，最多 +0.6
      const aiTerms = (this.aiTerms || []).filter(t => t && String(t).length >= 2);
      let aiBonus = 0;
      for (const term of aiTerms) {
        if (text.indexOf(term) >= 0) {
          aiBonus += 0.2;
          if (aiBonus >= 0.6) break;
        }
      }

      return score + aiBonus;
    },
    rowHasKeyword(row) {
      return this.rowMatchScore(row) >= 1;
    },
    // 判断标题或任意正文片段中是否包含原词或任意扩展词（用于过滤无高亮数据）
    rowHasAnyHighlight(row) {
      const kw = (this.searchedKeyword || '').trim();
      if (!kw) return true;
      const terms = [kw, ...(this.hlTerms || [])].filter(t => t && String(t).trim().length >= 1);
      if (!terms.length) return true;
      const text = String(row.title || '') + ' ' + (row.results ? row.results.map(r => (r && r.content) ? r.content : '').join(' ') : '');
      const lowerText = text.toLowerCase();
      return terms.some(t => lowerText.indexOf(String(t).toLowerCase()) >= 0);
    },
    // 优先选择包含关键词的片段展示，确保用户能看到高亮
    bestFragment(row) {
      if (!row.results || !row.results.length) return null;
      const kw = (this.searchedKeyword || '').trim();
      const terms = [kw, ...(this.hlTerms || [])].filter(t => t && String(t).trim().length >= 1);
      if (!terms.length || !kw) return row.results[0];
      for (const frag of row.results) {
        const text = String(frag && frag.content || '').toLowerCase();
        if (terms.some(t => text.indexOf(String(t).toLowerCase()) >= 0)) return frag;
      }
      return row.results[0];
    },
    resultTypeFormatter(val, types){
      if (val === 'document') return '公文正文';
      if (val === 'attachments') return '公文附件';
      // content 字段来自 oa_publish_content，按真实分类(types)显示，不再一律“新闻内容”
      const m = { '1': '集团新闻', '2': '子公司动态', '3': '通知公告', '4': '公文公告', '5': '信息公开' };
      return m[String(types)] || '发布内容';
    },
    // 结果分类标签颜色
    resultTypeTag(val, types) {
      if (val === 'document') return 'primary';
      if (val === 'attachments') return 'info';
      const m = { '1': 'success', '2': 'success', '3': 'warning', '4': 'warning', '5': 'danger' };
      return m[String(types)] || 'info';
    },
    // 附件图标 + 颜色：按文件扩展名区分（PDF红/DOCX蓝/XLSX绿/OFD紫/其它灰）
    fileTypeIcon(title) {
      const ext = String(title || '').split('.').pop().toLowerCase();
      const map = {
        pdf:  { cls: 'el-icon-document', color: '#d93025' },
        doc:  { cls: 'el-icon-document', color: '#0171b2' },
        docx: { cls: 'el-icon-document', color: '#0171b2' },
        wps:  { cls: 'el-icon-document', color: '#0171b2' },
        xls:  { cls: 'el-icon-document', color: '#188038' },
        xlsx: { cls: 'el-icon-document', color: '#188038' },
        csv:  { cls: 'el-icon-document', color: '#188038' },
        ofd:  { cls: 'el-icon-document', color: '#7c4dff' },
        zip:  { cls: 'el-icon-box', color: '#5f6368' },
        rar:  { cls: 'el-icon-box', color: '#5f6368' },
        jpg:  { cls: 'el-icon-picture-outline', color: '#e8710a' },
        jpeg: { cls: 'el-icon-picture-outline', color: '#e8710a' },
        png:  { cls: 'el-icon-picture-outline', color: '#e8710a' },
      };
      return map[ext] || { cls: 'el-icon-document', color: '#9aa0a6' };
    },
    // 结果卡片左侧竖线颜色
    getCategoryColor(item) {
      const type = item && item.bestFragment ? item.bestFragment.type : '';
      const types = item ? item.types : '';
      if (type === 'document' || type === 'attachments') return '#0171b2';
      const m = { '1': '#34a853', '2': '#34a853', '3': '#f9ab00', '4': '#f9ab00', '5': '#ea4335' };
      return m[String(types)] || '#5f6368';
    },
    openTargetFile(obj){
      let {url,title,fileId} = obj
      let params = {
        fileId,
        fileName:title,
        filePath:url,
        fileType:title.split('.').pop(),
      }
      // downLoadByCode(params);
      // var res = await loadLastAttach(params);
      // console.log('loadLastAttach', res);
      // if(res.data != null){
      //   row = res.data;
      // }
      if(['wps','doc','docx','xlsx','xls','et','csv'].indexOf(params.fileType.toLowerCase()) > -1){
        let revisionCtrl = {
          // 修订模式
          bOpenRevision: true,
          bShowRevision: true
        }
        const emptyCallback = () => {};
        this.$refs.wpsShow.openWps(params.fileId, params.fileName,true,{bookmark:{},setBasic: this.setBasic,disableBtns: 'grpWPSWork,refresh',revisionCtrl:revisionCtrl},this.setFormBookMakrs,emptyCallback );
      }else{
        // filePreview(row);
        downLoadByCode(params);
      }
    },
    // 处理不显示标签样式
    stripHtml(html) {
      const tempDiv = document.createElement('div');
      tempDiv.innerHTML = html;

      const processNode = (node) => {
        if (node.nodeType === Node.TEXT_NODE) return;

        if (node.tagName === 'SPAN') {
          // 处理SPAN标签的样式
          const style = node.getAttribute('style');
          if (style) {
            // 正则表达式移除font-size属性（包括各种单位和格式）
            const newStyle = style.replace(/\s*font-size\s*:\s*[^;]+;?/gi, '').trim();

            if (newStyle === '') {
              node.removeAttribute('style');
            } else {
              node.setAttribute('style', newStyle);
            }
          }

          // 检查是否为需要保留的特定格式
          if (node.getAttribute('style') === 'color:red;font-weight:bold') {
            const newSpan = document.createElement('span');
            newSpan.setAttribute('style', 'color:red;font-weight:bold');
            newSpan.textContent = node.textContent;
            node.parentNode.replaceChild(newSpan, node);
            return;
          }
        }

        // 递归处理子节点
        Array.from(node.childNodes).forEach(processNode);

        // 非SPAN标签处理：移除标签但保留内容
        if (node.tagName !== 'SPAN') {
          const parent = node.parentNode;
          Array.from(node.childNodes).forEach(child => {
            parent.insertBefore(child, node);
          });
          parent.removeChild(node);
        }
      };

      Array.from(tempDiv.childNodes).forEach(processNode);
      return tempDiv.innerHTML;
    },
    butClick(index, title) {
      if (title == 'method') {
        this.queryParams.method = index
      } else {
        this.queryParams.type = index
      }
      this.getList()
    },
    getExtension(item) {
      const lastIndex = item.lastIndexOf('.');
      if (lastIndex !== -1) {
        const extension = item.slice(lastIndex + 1);
        return extension;
      }
      return "";
    },
    toDetail(row) {
      console.log("toDetail>>>>>>>",row);
      let types = this.queryParams.type;
      if(!types){
        types = row.types?row.types:'99'
      }

      if (types == '99') {
        let user = this.$store.state.user;
        const hisTasks = JSON.parse(row.hisTasks);
        const tasks = hisTasks.filter(item=> item.userId==user.userId)
        console.log("tasks", tasks)
        this.$router.push({
          path: '/fullSearch/flw/detail',
          query: {
            taskId: tasks && tasks.length > 0 ? tasks[0].taskId : row.taskId,
            isView: true,
            isFullSearch:true
          }
        })
      } else {
        this.$router.push({
          name: 'moreListDetail',
          params: {
            id: row.id,
            esIndex: row.esIndex,
            types: types,
            isFullSearch:true,
            num: Math.random()+''
          }
        })
      }

    },
  }
}
</script>
<style lang="scss" scoped>
@import "~@/assets/styles/variables.scss";

@keyframes sk-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

::v-deep .pagination-container {
  position: initial;

}

::v-deep .el-pagination {
  position: initial;

}

// AI 扩词彩虹环：可动画的角度变量 + 关键帧
@property --aiang {
  syntax: '<angle>';
  initial-value: 0deg;
  inherits: false;
}
@keyframes aiRingSpin { to { --aiang: 360deg; } }
@keyframes aiPillFlow { to { background-position: 300% 0; } }

// 搜索面板卡片：整体吸顶（搜索框+标签页），下滑时内部区块平滑收起
.fs-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px 24px 14px;
  margin-bottom: 0;
  box-shadow: none;
  border: none;
  position: sticky;
  top: 0;
  z-index: 50;
  transition: padding .28s ease;
}

.fs-search-wrap {
  display: flex;
  justify-content: center;
  width: 100%;
  background: #fff;
  padding: 8px 0 4px;
  margin-top: -8px;
  transition: padding .28s ease, margin .28s ease;
}

.g-search {
  position: relative;
  width: 50%;
  max-width: 600px;
  margin: 0 0 12px 0;
  display: flex;
  align-items: center;
  height: 44px;
  padding: 0 8px 0 20px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 24px;
  transition: box-shadow .28s ease, height .28s ease, width .28s ease;

  &:hover,
  &.is-focus {
    box-shadow: none;
    border-color: transparent;
  }

  // AI 扩词开启：换彩虹环 + 稍快转速
  &.ai-enabled {
    background: transparent;
    border-color: transparent;

    .g-search-ring,
    .g-search-glow {
      background: conic-gradient(from var(--aiang), #ff3b30, #ff9500, #ffcc00, #34c759, #00c7be, #30b0ff, #5856d6, #af52de, #ff3b30);
      animation: aiRingSpin 4s linear infinite;
    }
    .g-search-glow { opacity: .35; }
  }

  // 彩虹三层（默认隐藏，开启时淡入）
  .g-search-glow,
  .g-search-ring {
    position: absolute;
    opacity: 0;
    pointer-events: none;
    transition: opacity .35s ease;
    border-radius: 24px;
  }

  // 白色内盖常驻：盖住蓝色渐变，让光环只露出边缘一圈（光圈而非满框填色）
  .g-search-fill {
    position: absolute;
    opacity: 1;
    pointer-events: none;
    transition: opacity .35s ease;
    border-radius: 24px;
  }

  .g-search-ring {
    top: -2.5px; left: -2.5px; right: -2.5px; bottom: -2.5px;
    border-radius: 26px;
    z-index: 0;
    background: conic-gradient(from var(--aiang), #7aa8f8, #d2e3fc, #a8c7fa, #e8f0fe, #7aa8f8);
    opacity: .9;
    animation: aiRingSpin 8s linear infinite;
  }

  .g-search-glow {
    top: -4px; left: -4px; right: -4px; bottom: -4px;
    border-radius: 28px;
    z-index: 0;
    filter: blur(6px);
    background: conic-gradient(from var(--aiang), #7aa8f8, #d2e3fc, #a8c7fa, #e8f0fe, #7aa8f8);
    opacity: .22;
    animation: aiRingSpin 8s linear infinite;
  }

  .g-search-fill {
    top: 0; left: 0; right: 0; bottom: 0;
    z-index: 1;
    background: #fff;
  }

  // 真实内容压在彩虹层之上
  > input,
  > i,
  > button,
  > ul { position: relative; z-index: 2; }

  .g-search-input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: transparent;
    font-size: 16px;
    color: #202124;
    height: 100%;
    padding-right: 6px;

    &::placeholder {
      color: #9aa0a6;
    }
  }

  .g-search-clear {
    color: #70757a;
    font-size: 18px;
    cursor: pointer;
    padding: 6px;
    margin-right: 4px;

    &:hover {
      color: #202124;
    }
  }

  .g-search-btn {
    flex-shrink: 0;
    width: 40px;
    height: 40px;
    padding: 0;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: $base-main-color;
    font-size: 18px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: background .2s ease;

    &:hover {
      background: #f1f3f4;
    }

    span { display: none; }
  }

  .g-search-ai-toggle {
    flex-shrink: 0;
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 30px;
    padding: 0 12px;
    margin-right: 8px;
    border: 1px solid #e0e3e9;
    border-radius: 15px;
    background: #fff;
    color: #5f6368;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    white-space: nowrap;
    transition: all .2s ease;

    .ai-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: #9aa0a6;
      transition: background .2s ease;
    }

    &:hover {
      background: #f8f9fa;
      border-color: #d2d4d7;
    }

    &.active {
      color: #fff;
      background: linear-gradient(120deg, #5856d6, #af52de, #ff3b30, #ff9500, #5856d6);
      background-size: 300% 100%;
      animation: aiPillFlow 4s linear infinite;
      border-color: transparent;
      box-shadow: 0 2px 10px rgba(175, 82, 222, 0.4);

      .ai-dot {
        background: #fff;
        box-shadow: 0 0 5px #fff;
      }
    }
  }

  .g-suggest {
    position: absolute;
    left: 0;
    right: 0;
    top: calc(100% + 6px);
    margin: 0;
    padding: 6px 0;
    list-style: none;
    background: #fff;
    border: 1px solid #e8eaed;
    border-radius: 16px;
    box-shadow: 0 4px 16px rgba(32, 33, 36, .18);
    z-index: 30;

    li {
      display: flex;
      align-items: center;
      padding: 8px 18px;
      cursor: pointer;
      font-size: 14px;
      color: #202124;

      .g-suggest-lead {
        color: #9aa0a6;
        margin-right: 14px;
        font-size: 15px;
      }

      .g-suggest-text {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .g-suggest-tag {
        font-size: 12px;
        color: #bdc1c6;
        margin-left: 10px;

        &.hot {
          color: #e6a23c;
        }
      }

      .g-suggest-del {
        color: #bdc1c6;
        font-size: 14px;
        margin-left: 10px;
        padding: 2px;

        &:hover {
          color: #70757a;
        }
      }

      &.active {
        background: #f1f3f4;
      }
    }
  }
}

.fs-filter {
  margin: 0;

  // 下拉组（现居 .fs-tabs-bar 内）：统一为「导航同款胶囊」，灰底无框与类别 tab 视觉一致
  .fs-filter-right {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;

    .fs-label {
      font-size: 13px;
      color: #909399;
    }

    ::v-deep .el-input__inner {
      height: 32px;
      line-height: 32px;
      border: none;
      border-radius: $base-menu-radius;
      font-size: 13px;
      color: #5f6368;
      padding: 0 10px 0 14px;
      background: #f1f3f4;
      transition: background .18s, color .18s;
    }
    ::v-deep .el-input__inner:hover,
    ::v-deep .el-select:hover .el-input__inner {
      background: #e6f3fa;
      color: #0171b2;
    }
    ::v-deep .el-input__inner:focus {
      border: none;
      background: #e6f3fa;
      color: #0171b2;
      outline: none;
    }
    ::v-deep .el-input__icon {
      line-height: 32px;
      color: #9aa0a6;
    }

    // 日期段编辑器同风格
    ::v-deep .el-date-editor.el-range-editor {
      background: #f1f3f4;
      border: none;
      border-radius: $base-menu-radius;
      height: 32px;
      padding: 0 10px;
      transition: background .18s;
    }
    ::v-deep .el-date-editor.el-range-editor:hover {
      background: #e6f3fa;
    }
    ::v-deep .el-range-input {
      background: transparent;
      font-size: 13px;
      color: #5f6368;
    }
    // 分隔符"至"与日期文字同色（#5f6368），垂直居中，不再偏淡突兀
    ::v-deep .el-range-separator {
      color: #5f6368;
      font-size: 13px;
      line-height: 1;
      display: inline-flex;
      align-items: center;
      padding: 0 4px;
    }
  }

  // 类别标签 + 筛选工具一行布局：分类在左、筛选在右，分隔线横跨整行
  .fs-tabs-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 12px;
    border-bottom: 1px solid #f0f2f5;
    padding-bottom: 8px;
    margin-bottom: 14px;

    // 空间不足时筛选组整体换行并右对齐（承接原 fs-filter-bar 的换行行为）
    .fs-filter-right {
      margin-left: auto;
    }
  }

  .fs-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    position: relative;

    // 共享滑动胶囊：激活态背景由它承担，随点击在 tab 间滑动
    .fs-tab-bg {
      position: absolute;
      left: 0;
      top: 0;
      background: #0171b2;
      border-radius: $base-menu-radius;
      transition: transform .28s ease, width .28s ease, opacity .2s ease;
      z-index: 0;
      pointer-events: none;
    }

    .fs-tab {
      padding: 6px 14px;
      margin-right: 0;
      font-size: 13px;
      color: #5f6368;
      background: #f1f3f4;
      border-radius: $base-menu-radius;
      cursor: pointer;
      transition: background .18s, color .18s;
      position: relative;

      &:hover {
        background: #e6f3fa;
        color: #0171b2;
      }

      position: relative;
      z-index: 1;

      &.active {
        background: transparent;
        color: #fff;
        font-weight: 500;

        &::after {
          display: none;
        }
      }
    }
  }

  // 二级类型 + 结果数 同行容器：左类型、右计数
  .fs-subtabs-count-row {
    .fs-count {
      font-size: 13px;
      color: #a8b0ba;
      margin-right: 24px;

      b {
        color: inherit;
        font-weight: 400;
      }

      .fs-count-note {
        font-size: 12px;
        color: #9aa0a6;
      }

      .fs-multi-hint {
        font-size: 12px;
        color: #0171b2;
        background: #e6f3fa;
        border: 1px solid #bcd9ea;
        border-radius: 4px;
        padding: 1px 6px;
        margin-left: 6px;
      }

      .fs-many-hint {
        font-size: 12px;
        color: #b26a00;
        background: #fff8e6;
        border: 1px solid #ffe7ba;
        border-radius: 4px;
        padding: 1px 6px;
        margin-left: 6px;
      }

      .fs-scope-note {
        font-size: 12px;
        color: #bcc3cb;
        margin-left: 8px;
        cursor: default;
      }
    }
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 12px;
    margin-bottom: 10px;
  }

  // 公文二级类型行：文字+下划线次级样式，与主胶囊导航形成层级差
  .fs-subtabs {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 2px;
    margin-top: 2px;
    margin-left: 20px;
    position: relative;

    // 共享滑动下划线：激活态下划线由它承担，随点击在类型间滑动
    .fs-subtab-ink {
      position: absolute;
      bottom: 0;
      left: 0;
      height: 2px;
      background: #0171b2;
      border-radius: 1px;
      transition: transform .28s ease, width .28s ease, opacity .2s ease;
      pointer-events: none;
    }
    padding: 0;
    background: transparent;
    border-radius: 0;

    .fs-subtabs-label {
      font-size: 12px;
      color: #9aa0a6;
      margin-right: 8px;
    }

    .fs-subtab {
      position: relative;
      padding: 4px 10px 6px;
      font-size: 12px;
      color: #909399;
      background: transparent;
      border: none;
      border-radius: 0;
      border-bottom: 2px solid transparent;
      cursor: pointer;
      transition: color .18s, border-color .18s;

      &:hover { color: #0171b2; }

      &.active {
        color: #0171b2;
        border-bottom-color: transparent;
        font-weight: 500;
        background: transparent;
      }
    }
  }

  // 公文二级标签行：渐进式展开动画（高度展开 + 淡入 + 轻微下滑）
  // 展开稍慢(.28s)显得顺滑，收起稍快(.16s)不拖沓
  .subtabs-pop-enter-active {
    transition: opacity .28s ease, max-height .28s ease, transform .28s ease,
                margin .28s ease, padding .28s ease;
    overflow: hidden;
  }
  .subtabs-pop-leave-active {
    transition: opacity .16s ease, max-height .16s ease, transform .16s ease,
                margin .16s ease, padding .16s ease;
    overflow: hidden;
  }
  .subtabs-pop-enter,
  .subtabs-pop-leave-to {
    opacity: 0;
    max-height: 0;
    transform: translateY(-8px);
    margin-top: 0;
    margin-bottom: 0;
    padding-top: 0;
    padding-bottom: 0;
  }
  .subtabs-pop-enter-to,
  .subtabs-pop-leave {
    opacity: 1;
    max-height: 120px;  // 覆盖换行场景（窄屏 chips 折行时 2~3 行高度）
    transform: translateY(0);
  }

  .fs-filter-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 16px;
    margin-top: 0;
    padding-top: 0;
    border-top: none;




  }
}

.full-search-content {
  flex-grow: 1;
  overflow: auto;
  padding: 0 $base-content-padding 0;
  background: white;
  border-radius: $base-menu-radius;
  display: flex;
  flex-direction: column;
  margin-top: 8px;

  .full-search-content-all {
    flex-grow: 1;
    transition: opacity .2s ease;
  }

  // 重新搜索中：旧列表压暗+禁点，提示正在加载，页面高度保持稳定不跳闪
  &.is-reloading .full-search-content-all {
    opacity: .45;
    pointer-events: none;
  }

  // 加载骨架屏
  .skeleton-wrap {
    padding: 8px 0;

    .skeleton-card {
      padding: 14px 8px 16px;
      margin-bottom: 0;
      border: none;
      border-radius: 0;
      border-bottom: 1px solid #f0f2f5;
    }

    .sk-line {
      height: 14px;
      border-radius: 4px;
      background: linear-gradient(90deg, #f2f3f5 25%, #e8eaed 37%, #f2f3f5 63%);
      background-size: 400% 100%;
      animation: sk-shimmer 1.4s ease infinite;
      margin-bottom: 10px;
    }

    .sk-title { width: 40%; height: 22px; margin-bottom: 8px; }
    .sk-text { width: 100%; }
    .sk-text.short { width: 70%; }
    .sk-meta { width: 30%; height: 12px; margin-bottom: 0; }
  }

  // 空状态
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 56px 0;
    color: #5f6368;

    .empty-icon {
      font-size: 52px;
      color: #dadce0;
      margin-bottom: 20px;
    }

    .empty-title {
      font-size: 16px;
      color: #202124;
      margin-bottom: 12px;
    }

    .empty-tip {
      font-size: 14px;
      color: #5f6368;

      .did-you-mean {
        color: #0171b2;
        font-weight: 500;
        cursor: pointer;
        text-decoration: none;
      }
      .did-you-mean:hover { text-decoration: underline; }

      // 零结果"开启 AI 扩词"引导链接
      .ai-expand-cta {
        color: #e6a23c;
        font-weight: 600;
        margin: 0 4px;
        padding: 2px 10px;
        border: 1px solid #f0c78a;
        border-radius: 12px;
        cursor: pointer;
        text-decoration: none;
        transition: all .18s;
      }
      .ai-expand-cta:hover {
        background: #e6a23c;
        color: #fff;
      }
    }

    .empty-scope {
      margin-top: 10px;
      font-size: 12px;
      color: #9aa0a6;
    }

    // 初始空态扩展区：热门搜索 / 搜索历史 / 检索小技巧
    .empty-section {
      margin-top: 18px;
      display: flex;
      flex-direction: column;
      align-items: center;

      .empty-section-title {
        font-size: 13px;
        color: #909399;
        margin-bottom: 10px;

        i {
          margin-right: 4px;
          color: #0171b2;
        }
      }

      .empty-chips {
        display: flex;
        flex-wrap: wrap;
        justify-content: center;
        gap: 8px;
        max-width: 680px;
      }

      .empty-chip {
        display: inline-flex;
        align-items: center;
        padding: 5px 14px;
        font-size: 13px;
        color: #5f6368;
        background: #fff;
        border: 1px solid #e0e3e9;
        border-radius: $base-menu-radius;
        cursor: pointer;
        transition: all .18s;

        &:hover {
          color: #0171b2;
          border-color: #0171b2;
          background: #e6f3fa;
        }

        &.hot {
          color: #b26a00;
          background: #fff8e6;
          border-color: #ffe7ba;

          &:hover {
            background: #ffefc2;
            border-color: #e6a23c;
            color: #b26a00;
          }
        }

        .empty-chip-x {
          margin-left: 6px;
          font-size: 12px;
          color: #c0c4cc;
          border-radius: 50%;
          padding: 1px;

          &:hover {
            color: #fff;
            background: #c0c4cc;
          }
        }
      }
    }

    .empty-tips {
      margin-top: 28px;
      max-width: 600px;
      text-align: left;
      background: #f8f9fa;
      border: 1px solid #f0f2f5;
      border-radius: $base-menu-radius;
      padding: 14px 18px;

      .empty-tips-title {
        font-size: 13px;
        color: #606266;
        font-weight: 500;
        margin-bottom: 8px;
      }

      .empty-tip-item {
        font-size: 12px;
        color: #909399;
        line-height: 2;

        i {
          margin-right: 6px;
          color: #0171b2;
        }
      }
    }
  }

  .load-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 16px 0 24px;
    gap: 8px;

    .load-more-status {
      color: #909399;
      font-size: 12px;
    }

    .load-more-text {
      color: #70757a;
      font-size: 13px;
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .back-to-top {
      color: #0171b2;
      font-size: 13px;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 4px 12px;
      border-radius: 4px;
      transition: background .2s;

      &:hover {
        background: #f1f3f4;
      }
    }
  }

  .result-card {
    cursor: pointer;
    margin: 0 0 12px;
    padding: 16px 18px 14px;
    background: #fff;
    border: 1px solid #e3e7ec;
    border-radius: 10px;
    transition: box-shadow .18s, border-color .18s, transform .18s;

    &:hover {
      background: #fff;
      border-color: #d8e2f0;
      box-shadow: 0 4px 16px rgba(26,115,232,.10);
      transform: translateY(-1px);

      .result-title-text {
        text-decoration: underline;
      }
    }

    .result-title {
      display: flex;
      align-items: center;
      font-size: 20px;
      line-height: 1.3;
      margin-bottom: 6px;

      .related-tag {
        flex-shrink: 0;
        background: #f1f3f4;
        color: #5f6368;
        border-radius: 4px;
        padding: 2px 8px;
        font-size: 12px;
        font-weight: 500;
        margin-right: 8px;
        line-height: 1.4;
      }

      .result-title-text {
        flex-grow: 1;
        color: #1a50b0;
        font-weight: 500;
        width: 0;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;

        ::v-deep span[style*="red"] {
          background: transparent !important;
          color: #f56c6c !important;
          font-weight: 600;
        }
      }
    }

    .result-frag {
      .result-frag-con {
        font-size: 14px;
        color: #4d5156;
        overflow: hidden;
        text-overflow: ellipsis;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        line-height: 1.58;

        ::v-deep span[style*="red"] {
          background: transparent !important;
          color: #f56c6c !important;
          font-weight: bold;
        }
      }

      .result-meta {
        color: #70757a;
        font-size: 12px;
        margin-top: 6px;
        line-height: 1.4;

        .meta-item {
          margin-right: 16px;

          i {
            margin-right: 4px;
            color: #9aa0a6;
          }
        }

        .meta-more {
          color: #70757a;
          background: transparent;
          border-radius: 0;
          padding: 0;
          font-size: 12px;
        }

        .semantic-badge {
          display: inline-flex;
          align-items: center;
          font-size: 11px;
          color: #7c6bd6;
          background: #f0edfb;
          border: 1px solid #dcd6f5;
          border-radius: 3px;
          padding: 0 6px;
          height: 18px;
          line-height: 18px;
          margin-left: 8px;
        }
      }
    }
  }
}
.file-link {
  color: #0171b2;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
    color: #015d94;
  }
}

// AI 联想词条：黄色主题 chips
.ai-suggest {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin: 8px 0 0;
  padding: 6px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  font-size: 13px;
  color: #7a5b1c;

  .ai-badge {
    background: #e6a23c;
    color: #fff;
    border-radius: 4px;
    padding: 1px 7px;
    margin-right: 8px;
    font-size: 12px;
    font-weight: bold;
  }

  .ai-suggest-label {
    margin-right: 4px;
  }

  .ai-suggest-tip {
    margin-left: auto;
    font-size: 12px;
    color: #a8a29e;

    .tip-red {
      color: #f56c6c;
      font-style: normal;
      font-weight: 600;
    }

    .tip-yellow {
      color: #f0ad4e;
      font-style: normal;
      font-weight: 600;
    }
  }
}

.ai-chip {
  display: inline-flex;
  align-items: center;
  background: #fff;
  color: #e6a23c;
  border: 1px solid #f0c78a;
  border-radius: $base-menu-radius;
  padding: 2px 8px 2px 12px;
  margin: 3px 5px 3px 0;
  cursor: pointer;
  transition: all .2s;
  user-select: none;

  &:hover {
    background: #e6a23c;
    color: #fff;
    border-color: #e6a23c;

    .ai-chip-x {
      opacity: 1;
      visibility: visible;
      color: #fff;
      background: rgba(255, 255, 255, .22);
    }
  }

  .ai-chip-x {
    margin-left: 6px;
    font-size: 12px;
    color: #d38b1d;
    border-radius: 50%;
    padding: 1px;
    // 移除按钮常显：浅色圆底让叉叉一眼可见，不用悬停才出现
    opacity: 1;
    visibility: visible;
    background: rgba(230, 162, 60, .14);
    transition: all .15s;

    &:hover {
      color: #fff;
      background: rgba(0, 0, 0, .18);
    }
  }
}

// 相关搜索推荐：蓝色主题 chips
.related-searches {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin: 0 0 16px;
  padding: 10px 14px;
  background: #f0f7ff;
  border: 1px solid #d9ecff;
  border-radius: 8px;
  font-size: 13px;
  color: #1a5b9e;

  .related-label {
    margin-right: 8px;
  }

  .related-chip {
    display: inline-flex;
    align-items: center;
    background: #fff;
    color: #0171b2;
    border: 1px solid #a8d3ff;
    border-radius: $base-menu-radius;
    padding: 2px 12px;
    margin: 3px 5px 3px 0;
    cursor: pointer;
    transition: all .2s;
    user-select: none;

    &:hover {
      background: #0171b2;
      color: #fff;
      border-color: #0171b2;
    }
  }
}

// ===== 下滑收缩：一行压条——左=类别标签胶囊，中=搜索框，右=筛选下拉组 =====
// 迟滞区间在 scrollHandler 里（>220 收、<120 展），搜索框高宽走 transition 平滑过渡
.app-container-new {
  &.is-stuck {
    // 面板变弹性容器：三组一行，space-between 让左中右自然拉开间隔
    .fs-panel {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      column-gap: 16px;
      padding: 6px 24px;
    }

    // 类别标签（左）：压缩成小胶囊；行内状态不显示底部分隔线
    // flex:1 与右侧筛选组等宽，保证中间搜索框视觉居中（space-between 侧组不等宽会挤偏）
    .fs-tabs {
      order: 1;
      flex: 1 1 0;
      min-width: 0;
      margin-bottom: 0;
      border-bottom: none;
      padding-bottom: 0;
      transition: margin .28s ease;

      .fs-tab {
        padding: 2px 8px;
        font-size: 14px;
        transition: background .18s, color .18s, padding .28s ease, font-size .22s ease;
      }
    }

    // 收缩态整行控件字号统一 14px，并同步缩小框内按钮，避免顶到边框
    .g-search-input {
      font-size: 14px;
      transition: font-size .22s ease;
    }

    // AI 扩词按钮缩小：34px 框内留出上下间隙
    .g-search-ai-toggle {
      height: 24px;
      padding: 0 9px;
      margin-right: 4px;
      font-size: 12px;
      gap: 4px;
      border-radius: 12px;

      .ai-dot {
        width: 6px;
        height: 6px;
      }
    }

    // 放大镜按钮缩小：原 40px 比收缩后的框还高
    .g-search-btn {
      width: 26px;
      height: 26px;
      font-size: 15px;
    }

    // 右侧下拉字号与整行一致
    .fs-filter-right {
      ::v-deep .el-input__inner {
        font-size: 14px;
      }
    }

    // 搜索框（中）：基准宽 520px，可缩不撑满
    .fs-search-wrap {
      display: block;
      flex: 0 1 520px;
      order: 2;
      min-width: 0;
      padding: 0;
      margin-top: 0;
    }

    .g-search {
      height: 34px;
      width: 100%;
      max-width: 520px;
      margin-bottom: 0;
    }

    // 筛选区（右）：外层打散，三个下拉作为一个整体组排在最右
    // fs-tabs-bar 也打散：让「类别标签/筛选组」重新参与面板级 space-between 布局
    .fs-filter,
    .fs-filter-bar,
    .fs-tabs-bar {
      display: contents;
    }

    .fs-count {
      display: none;
    }

    .fs-filter-right {
      order: 3;
      flex: 1 1 0;
      min-width: 0;
      display: flex;
      align-items: center;
      justify-content: flex-end;
      flex-wrap: nowrap;
      gap: 8px;
    }

    // 公文二级标签行收缩态隐藏
    .fs-subtabs {
      display: none;
    }

    // 二级类型+计数行收缩态整体隐藏
    .fs-subtabs-count-row {
      display: none;
    }
  }
}

// ===== 窄屏适配：收缩态下浏览器变窄（<1280px）时，搜索框优先压缩、
// 右侧下拉组允许收缩换行，防止「全部部门」被顶出屏幕 =====
@media (max-width: 1280px) {
  .app-container-new.is-stuck {
    // 搜索框从「基准 520 可缩」改为「尽量压缩」，给下拉组让位
    .fs-search-wrap {
      flex: 1 1 220px;
    }

    // 下拉组允许被压缩，放不下时换行右对齐，不再硬撑一整行
    .fs-filter-right {
      flex: 0 1 auto;
      flex-wrap: wrap;
      justify-content: flex-end;
      row-gap: 6px;
    }

    // 部门下拉内联固定 320px 在窄屏太占宽，改弹性宽度（需 !important 覆盖内联样式）
    .fs-sel-dept {
      width: 200px !important;
    }

    // 自定义日期段也同步收窄
    .fs-sel-range {
      width: 220px !important;
    }
  }
}

// ===== 超窄屏适配（<900px）：收缩态整行改为纵向堆叠，
// 类别标签一行、搜索框独占整行、筛选下拉一行右对齐，避免搜索框被挤变形 =====
@media (max-width: 900px) {
  .app-container-new.is-stuck {
    // 搜索框独立成行，占满整行宽度
    .fs-search-wrap {
      flex: 1 1 100%;
      order: 2;
      margin-top: 6px;
    }

    .g-search {
      max-width: none;  // 解除 520px 上限，独占整行时铺满
    }

    // 类别标签保持第一行
    .fs-tabs {
      order: 1;
    }

    // 筛选下拉放最后一行，右对齐
    .fs-filter-right {
      order: 3;
      flex: 1 1 100%;
      justify-content: flex-end;
      margin-top: 6px;
    }
  }
}
</style>
