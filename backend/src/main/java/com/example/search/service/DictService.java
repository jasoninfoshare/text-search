package com.example.search.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 本地词典服务：查询期用 ext_dict 做专名分词、用 synonym 做同义词扩展，不依赖 ES 的 IK。
 * 读 es.dict.dir 下的 ext_dict.txt / synonym.txt，按文件改动自动重载。
 */
@Slf4j
@Service
public class DictService {

    @Value("${es.dict.dir:config/dict}")
    private String dictDir;

    /** 同义词扩展最多追加的词数 */
    @Value("${es.dict.synonymMax:12}")
    private int synonymMax;

    private volatile Set<String> extDict = Collections.emptySet();
    private volatile int extMaxLen = 0;
    private volatile Map<String, List<String>> synMap = Collections.emptyMap();
    private volatile Map<String, List<String>> expandPatternMap = Collections.emptyMap();
    /** 拼音/首字母 -> 中文词（pinyin.txt，离线生成） */
    private volatile Map<String, List<String>> pinyinMap = Collections.emptyMap();
    private volatile long extMtime = -1, synMtime = -1, patternMtime = -1, zeroMtime = -1, pinyinMtime = -1;
    private volatile long lastCheck = 0;

    /** 专名分词时，间隙里这些噪声词不单独作为 AND 条件 */
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "关于", "的", "和", "与", "及", "等", "在", "对", "为", "通知", "请示", "报告", "批复", "函",
            "公告", "纪要", "意见", "方案", "办法", "规定", "情况", "工作", "有关", "相关", "我司", "我公司"));

    private synchronized void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 5000) return;
        lastCheck = now;
        File ext = new File(dictDir, "ext_dict.txt");
        if (ext.exists() && ext.lastModified() != extMtime) {
            try {
                Set<String> s = new HashSet<>();
                int max = 0;
                for (String line : Files.readAllLines(ext.toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.length() >= 2) { s.add(t); if (t.length() > max) max = t.length(); }
                }
                extDict = s; extMaxLen = max; extMtime = ext.lastModified();
                log.info("加载 ext_dict {} 词", s.size());
            } catch (Exception e) {
                log.warn("加载 ext_dict 失败: {}", e.getMessage());
            }
        }
        File syn = new File(dictDir, "synonym.txt");
        File zero = new File(dictDir, "zero_words.txt");
        boolean synChanged = syn.exists() && syn.lastModified() != synMtime;
        boolean zeroChanged = zero.exists() && zero.lastModified() != zeroMtime;
        // 只有 synonym 或 zero_words 真的变化时才重建 synMap；
        // 否则 m 是空表，直接覆盖会把已加载的同义词清空（5 秒后全部失效）
        if (synChanged || zeroChanged) {
            Map<String, List<String>> m = new HashMap<>();
            if (syn.exists()) {
                try {
                    for (String line : Files.readAllLines(syn.toPath(), StandardCharsets.UTF_8)) {
                        List<String> grp = new ArrayList<>();
                        for (String w : line.split(",")) {
                            String x = w.trim();
                            if (!x.isEmpty()) grp.add(x);
                        }
                        if (grp.isEmpty()) continue;
                        if (grp.size() == 1) {
                            // 单个词：自身一组，表示"不扩词"
                            m.computeIfAbsent(grp.get(0), k -> new ArrayList<>()).add(grp.get(0));
                        } else {
                            for (String w : grp) m.computeIfAbsent(w, k -> new ArrayList<>()).addAll(grp);
                        }
                    }
                    synMtime = syn.lastModified();
                    log.info("加载 synonym {} 词条", m.size());
                } catch (Exception e) {
                    log.warn("加载 synonym 失败: {}", e.getMessage());
                }
            }
            // 0 结果高频词：作为自身同义词组，表示"不扩词"
            if (zero.exists()) {
                try {
                    for (String line : Files.readAllLines(zero.toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        // 自身一组，synonyms() 会返回空，从而不走 AI 扩词
                        m.computeIfAbsent(t, k -> new ArrayList<>()).add(t);
                    }
                    zeroMtime = zero.lastModified();
                    log.info("加载 zero_words 后累计 {} 词条", m.size());
                } catch (Exception e) {
                    log.warn("加载 zero_words 失败: {}", e.getMessage());
                }
            }
            synMap = m;
        }
        File pattern = new File(dictDir, "expand_patterns.txt");
        if (pattern.exists() && pattern.lastModified() != patternMtime) {
            try {
                Map<String, List<String>> patternMap = new LinkedHashMap<>();
                for (String line : Files.readAllLines(pattern.toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    int sep = t.indexOf("->");
                    if (sep < 0) continue;
                    String core = t.substring(0, sep).trim();
                    String tplPart = t.substring(sep + 2).trim();
                    if (core.isEmpty() || tplPart.isEmpty()) continue;
                    List<String> tpls = new ArrayList<>();
                    for (String tpl : tplPart.split(",")) {
                        String x = tpl.trim();
                        if (!x.isEmpty() && x.contains("{prefix}")) tpls.add(x);
                    }
                    if (!tpls.isEmpty()) patternMap.put(core, tpls);
                }
                expandPatternMap = patternMap; patternMtime = pattern.lastModified();
                log.info("加载 expand_patterns {} 模板", patternMap.size());
            } catch (Exception e) {
                log.warn("加载 expand_patterns 失败: {}", e.getMessage());
            }
        }
        // 拼音/首字母词典（pinyin.txt：拼音,中文词 每行一条，离线生成）
        File py = new File(dictDir, "pinyin.txt");
        if (py.exists() && py.lastModified() != pinyinMtime) {
            try {
                Map<String, List<String>> pm = new HashMap<>();
                for (String line : Files.readAllLines(py.toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    int sep = t.indexOf(",");
                    if (sep <= 0) continue;
                    String k = t.substring(0, sep).trim().toLowerCase();
                    String v = t.substring(sep + 1).trim();
                    if (k.isEmpty() || v.isEmpty()) continue;
                    List<String> lst = pm.computeIfAbsent(k, x -> new ArrayList<>());
                    if (!lst.contains(v)) lst.add(v);
                }
                pinyinMap = pm; pinyinMtime = py.lastModified();
                log.info("加载 pinyin {} 词条", pm.size());
            } catch (Exception e) {
                log.warn("加载 pinyin 失败: {}", e.getMessage());
            }
        }
    }

    /** 拼音/首字母查中文词：仅对 2+ 个纯小写字母的查询生效，查不到返回空列表。 */
    public List<String> pinyinWords(String keyword) {
        if (keyword == null) return Collections.emptyList();
        reloadIfNeeded();
        String k = keyword.trim().toLowerCase();
        if (!k.matches("[a-z]{2,}")) return Collections.emptyList();
        List<String> r = pinyinMap.get(k);
        return r == null ? Collections.emptyList() : new ArrayList<>(r);
    }

    /**
     * 专名分词：对查询词正向最大匹配 ext_dict，识别出的专名当整词用空格隔开，交给 ES 的多关键词 AND 逻辑。
     * 未识别到任何专名则原样返回（不改变普通查询的行为）。
     */
    public String segment(String keyword) {
        if (keyword == null) return null;
        reloadIfNeeded();
        if (extDict.isEmpty()) return keyword;
        String kw = keyword.trim();
        if (kw.isEmpty()) return keyword;
        List<String> out = new ArrayList<>();
        boolean hitProper = false;
        for (String piece : kw.split("[\\s\\u3000]+")) {
            if (piece.isEmpty()) continue;
            int n = piece.length(), i = 0, gapStart = 0;
            List<String> tokens = new ArrayList<>();
            while (i < n) {
                int matchLen = 0;
                int maxL = Math.min(extMaxLen, n - i);
                for (int L = maxL; L >= 2; L--) {
                    if (extDict.contains(piece.substring(i, i + L))) { matchLen = L; break; }
                }
                if (matchLen > 0) {
                    if (gapStart < i) addGap(tokens, piece.substring(gapStart, i));
                    tokens.add(piece.substring(i, i + matchLen));
                    hitProper = true;
                    i += matchLen;
                    gapStart = i;
                } else {
                    i++;
                }
            }
            if (gapStart < n) addGap(tokens, piece.substring(gapStart));
            if (tokens.isEmpty()) tokens.add(piece);
            out.addAll(tokens);
        }
        if (!hitProper) return keyword;
        String seg = String.join(" ", out).trim();
        return seg.isEmpty() ? keyword : seg;
    }

    public String splitForRecall(String keyword) {
		if (keyword == null) return null;
		String kw = keyword.trim();
		int n = kw.length();
		if (n < 4 || n > 8) return keyword;
		for (int i = 0; i < n; i++) {
			char c = kw.charAt(i);
			if (c < 0x4E00 || c > 0x9FFF) return keyword;
		}
		List<String> chunks = new ArrayList<>();
		int i = 0;
		while (i < n) {
			int remain = n - i;
			if (remain == 3) { chunks.add(kw.substring(i, i + 3)); break; }
			if (remain == 1) {
				if (!chunks.isEmpty()) chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + kw.substring(i));
				break;
			}
			chunks.add(kw.substring(i, i + 2));
			i += 2;
		}
		if (chunks.size() < 2) return keyword;
		return String.join(" ", chunks);
	}

	private void addGap(List<String> tokens, String gap) {
        gap = gap.trim();
        if (gap.length() >= 2 && !STOP.contains(gap)) tokens.add(gap);
    }

    /**
     * 结构化扩词模板：当关键词以某个核心词结尾时，提取前缀并按模板生成扩展词。
     * 例如 "数字化先进" 命中核心词 "先进"，前缀为 "数字化"，生成 "数字化先进单位"。
     */
    public List<String> patternExpansions(String keyword) {
        if (keyword == null) return Collections.emptyList();
        reloadIfNeeded();
        if (expandPatternMap.isEmpty()) return Collections.emptyList();
        String kw = keyword.trim();
        if (kw.isEmpty()) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : expandPatternMap.entrySet()) {
            String core = e.getKey();
            if (kw.endsWith(core) && kw.length() > core.length()) {
                String prefix = kw.substring(0, kw.length() - core.length()).trim();
                if (prefix.isEmpty()) continue;
                for (String tpl : e.getValue()) {
                    String w = tpl.replace("{prefix}", prefix);
                    if (!w.equals(kw)) result.add(w);
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 返回同义词表中所有词条（用于搜索建议候选） */
    public Set<String> allSynonymWords() {
        reloadIfNeeded();
        return new HashSet<>(synMap.keySet());
    }

    /**
     * 导出给 IK 远程词典的完整词表：ext_dict 专名 + 同义词表全部词条，排序去重。
     * IK remote_ext_dict 每分钟轮询该接口实现词典热更新。
     */
    public Set<String> exportDictWords() {
        reloadIfNeeded();
        Set<String> all = new TreeSet<>();
        for (String w : extDict) if (!w.startsWith("#")) all.add(w);
        for (String w : synMap.keySet()) if (!w.startsWith("#")) all.add(w);
        return all;
    }

    /** 词典源文件的最新修改时间（供 IK 的 Last-Modified 变更检测） */
    public long dictLastModified() {
        long max = 0;
        // zero_words.txt 每晚被定时任务重写，也必须纳入变更检测，否则 IK 远程词典漏更新
        for (String name : new String[]{"ext_dict.txt", "synonym.txt", "pinyin.txt", "zero_words.txt"}) {
            File f = new File(dictDir, name);
            if (f.exists() && f.lastModified() > max) max = f.lastModified();
        }
        return max;
    }

    /** 常见姓氏，用于粗判人名 */
    private static final Set<String> SURNAMES = new HashSet<>(Arrays.asList(
            "张", "王", "李", "赵", "刘", "陈", "杨", "黄", "周", "吴", "徐", "孙", "马", "朱", "胡", "郭", "何", "林", "罗", "郑",
            "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹", "彭", "曾", "肖", "田", "董", "潘", "袁", "蔡", "蒋", "余", "于",
            "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈", "姚", "卢", "姜", "崔", "钟", "谭", "陆", "汪", "范", "金", "石",
            "廖", "贾", "夏", "韦", "付", "方", "白", "邹", "孟", "熊", "秦", "邱", "江", "尹", "薛", "闫", "段", "雷", "侯", "龙",
            "史", "陶", "黎", "贺", "顾", "毛", "郝", "龚", "邵", "万", "钱", "严", "覃", "武", "戴", "莫", "孔", "向", "汤"
    ));

    /** 判断是否为疑似人名：2-3 个汉字，以常见姓氏开头，且不在本地词典中 */
    public boolean isPersonOrOrg(String keyword) {
        if (keyword == null) return false;
        String kw = keyword.trim();
        if (kw.isEmpty()) return false;
        // 只匹配 2-3 个纯中文
        if (!kw.matches("[\\u4e00-\\u9fa5]{2,3}")) return false;
        // 不以常见姓氏开头，不是人名
        if (!SURNAMES.contains(String.valueOf(kw.charAt(0)))) return false;
        reloadIfNeeded();
        // 已在词典中的词不是人名
        if (synMap.containsKey(kw) || extDict.contains(kw)) return false;
        return true;
    }

    /**
     * 是否为"不扩词"标记词：zero_words 自动采集词，或 synonym.txt 中单词自成组的词。
     * 命中时查询端不应再走 AI 模型兜底扩词（这些词已知没有好的扩展）。
     */
    public boolean isNoExpand(String keyword) {
        if (keyword == null) return false;
        String kw = keyword.trim();
        if (kw.isEmpty()) return false;
        reloadIfNeeded();
        List<String> grp = synMap.get(kw);
        return grp != null && grp.size() == 1 && grp.get(0).equals(kw);
    }

    /** 同义词扩展：对关键词及其分词查同义组，返回去重、去原词、限量后的同义词。 */
    public List<String> synonyms(String keyword) {
        if (keyword == null) return Collections.emptyList();
        reloadIfNeeded();
        if (synMap.isEmpty()) return Collections.emptyList();
        Set<String> originals = new HashSet<>();
        String kw = keyword.trim();
        if (!kw.isEmpty()) originals.add(kw);
        for (String t : kw.split("[\\s\\u3000]+")) {
            if (!t.isEmpty()) originals.add(t);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String q : originals) {
            List<String> grp = synMap.get(q);
            if (grp != null) {
                for (String w : grp) {
                    if (!originals.contains(w)) result.add(w);
                }
            }
        }
        List<String> list = new ArrayList<>(result);
        if (list.size() > synonymMax) list = new ArrayList<>(list.subList(0, synonymMax));
        return list;
    }

    /**
     * 错别字纠正用：在【专名词典 + 同义词表】里找与 keyword 编辑距离最近的词。
     * 仅当存在足够相近（短词≤1、长词≤2）且长度接近的候选时返回，否则返回空串。
     */
    public String nearestTerm(String keyword) {
        if (keyword == null) return "";
        reloadIfNeeded();
        String kw = keyword.trim();
        int n = kw.length();
        if (n < 2 || n > 12) return "";
        int maxDist = n <= 3 ? 1 : 2;
        Set<String> cands = new HashSet<>(synMap.keySet());
        cands.addAll(extDict);
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String c : cands) {
            if (c == null || c.isEmpty() || c.equals(kw)) continue;
            if (Math.abs(c.length() - n) > 1) continue;
            int d = editDistance(kw, c, maxDist);
            if (d >= 1 && d <= maxDist && d < bestDist) { bestDist = d; best = c; if (d == 1) break; }
        }
        return best == null ? "" : best;
    }

    /** 带上限剪枝的编辑距离（超过 max 直接返回 max+1） */
    private int editDistance(String a, String b, int max) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > max) return max + 1;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i; int rowMin = cur[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[lb];
    }
}
