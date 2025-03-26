package xyz.ifnotnull.tmm.scraper.pornhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.core.entities.MediaGenres;
import org.tinymediamanager.core.entities.MediaRating;
import org.tinymediamanager.core.entities.MediaTrailer;
import org.tinymediamanager.core.entities.Person;
import org.tinymediamanager.core.movie.MovieSearchAndScrapeOptions;
import org.tinymediamanager.scraper.MediaMetadata;
import org.tinymediamanager.scraper.MediaProviderInfo;
import org.tinymediamanager.scraper.MediaSearchResult;
import org.tinymediamanager.scraper.entities.MediaArtwork;
import org.tinymediamanager.scraper.entities.MediaType;
import org.tinymediamanager.scraper.exceptions.ScrapeException;
import org.tinymediamanager.scraper.http.InMemoryCachedUrl;
import org.tinymediamanager.scraper.http.ProxySettings;
import org.tinymediamanager.scraper.http.TmmHttpClient;
import org.tinymediamanager.scraper.interfaces.IMovieMetadataProvider;
import org.tinymediamanager.scraper.util.MetadataUtil;
import xyz.ifnotnull.tmm.scraper.pornhub.dto.LdJson;

import java.io.IOException;
import java.net.Proxy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pornhub metadata scraper using Jsoup - 更高效的实现
 */
public class JsoupPornhubScraper implements IMovieMetadataProvider {
    public static final String ID = "pornhub";
    public static final String API_HOST = "pornhub.com";
    public static final String API_URL = "https://" + API_HOST;
    private static final Logger logger = LoggerFactory.getLogger(JsoupPornhubScraper.class);
    private static final Pattern ADD_DATE_REGEX = Pattern.compile("^(\\d+)\\s*(\\S+)\\s*(?:ago|前)$");
    private static final Pattern THUMB_URL_INDEX_PATTERN = Pattern.compile("\\{(\\d+)}");
    private static final String CONFIG_ID_MATCHER = "ID Matcher";
    private static final String CONFIG_ACCOUNT = "Pornhub Account";
    private static final String CONFIG_PASSWORD = "Pornhub Password";
    private static final int CACHE_TIMEOUT = 60; // 缓存时间（分钟）
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaProviderInfo providerInfo;
    // 使用缓存机制提高性能
    private final Map<String, InMemoryCachedUrl> urlCache = new HashMap<>();

    public JsoupPornhubScraper() {
        providerInfo = createProviderInfo();
    }

    private MediaProviderInfo createProviderInfo() {
        MediaProviderInfo info = new MediaProviderInfo(ID, "movie", "Pornhub", "Scraper addon for Pornhub",
                JsoupPornhubScraper.class.getResource("/xyz/ifnotnull/tmm/scraper/pornhub/pornhub_logo.svg"));
        // the ResourceBundle to offer i18n support for scraper options
        info.setResourceBundle(ResourceBundle.getBundle("xyz.ifnotnull.tmm.scraper.pornhub.messages"));

        // create configuration properties
        info.getConfig().addText(CONFIG_ID_MATCHER, "^(\\w+?)\\s*[|!@].*", false);
        info.getConfig().addText(CONFIG_ACCOUNT, "", false);
        info.getConfig().addText(CONFIG_PASSWORD, "", true);

        // load any existing values from the storage
        info.getConfig().load();

        return info;
    }

    @Override
    public MediaProviderInfo getProviderInfo() {
        return providerInfo;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public SortedSet<MediaSearchResult> search(MovieSearchAndScrapeOptions options) throws ScrapeException {
        logger.debug("search(): {}", options);
        SortedSet<MediaSearchResult> results = new TreeSet<>();

        String phId = options.getIdAsString(getId());

        // 尝试从选项中获取ID
        if (StringUtils.isEmpty(phId)) {
            // 尝试从文件名中提取ID
            String idMatcher = getProviderInfo().getConfig().getValue(CONFIG_ID_MATCHER);
            Matcher matcher = Pattern.compile(idMatcher).matcher(options.getSearchQuery());
            if (matcher.matches()) {
                phId = matcher.group(1);
            }
        }

        // 如果有ID，直接获取元数据
        if (StringUtils.isNotEmpty(phId)) {
            options.setId(getId(), phId);
            MediaMetadata metadata = getMetadata(options);
            if (metadata != null) {
                MediaSearchResult result = metadata.toSearchResult(MediaType.MOVIE);
                result.setMetadata(metadata);
                // 添加标准ID
                result.setId(MediaMetadata.IMDB, metadata.getId(MediaMetadata.IMDB).toString());
                // 确保设置海报
                for (MediaArtwork mediaArtwork : metadata.getMediaArt(MediaArtwork.MediaArtworkType.POSTER)) {
                    result.setPosterUrl(mediaArtwork.getOriginalUrl());
                    break;
                }
                // 确保设置年份
                result.setYear(metadata.getYear());
                // 确保设置原始标题
                result.setOriginalTitle(metadata.getOriginalTitle());
                // 设置最高分数确保被选中
                result.setScore(1);
                results.add(result);
                return results;
            }
        }

        // 没有ID，通过文件名搜索
        String searchString = MetadataUtil.removeNonSearchCharacters(options.getSearchQuery());
        if (StringUtils.isNotEmpty(searchString)) {
            try {
                // 执行搜索请求
                String searchUrl = API_URL + "/video/search?search=" + searchString.replace(" ", "+");
                Document doc = getDocument(searchUrl);
                
                // 查找搜索结果
                Elements searchResults = doc.select("#videoSearchResult li.pcVideoListItem");
                
                // 限制结果数量为前8个
                int maxResults = Math.min(8, searchResults.size());
                for (int i = 0; i < maxResults; i++) {
                    Element result = searchResults.get(i);
                    Element img = result.selectFirst("img");
                    if (img == null) continue;
                    
                    String title = img.attr("alt");
                    if (StringUtils.isEmpty(title)) {
                        title = img.attr("data-title");
                    }

                    MediaSearchResult sr = new MediaSearchResult(getId(), MediaType.MOVIE);
                    // 设置标准ID
                    String videoKey = result.attr("data-video-vkey");
                    String videoId = result.attr("data-video-id");
                    sr.setId(getId(), videoKey);
                    sr.setId(getId() + "_id", videoId);
                    // 添加IMDB ID (TMM内部使用)
                    sr.setId(MediaMetadata.IMDB, "ph" + videoId);
                    
                    sr.setTitle(title);
                    sr.setOriginalTitle(title);

                    // 解析上传日期
                    Element addedElement = result.selectFirst(".added");
                    if (addedElement != null) {
                        String addedDate = addedElement.text().trim();
                        Matcher numRegex = ADD_DATE_REGEX.matcher(addedDate);
                        LocalDateTime time = LocalDateTime.now();
                        if (numRegex.find()) {
                            switch (numRegex.group(2)) {
                                case "年":
                                case "year":
                                case "years":
                                    time = time.minusYears(Long.parseLong(numRegex.group(1)));
                                    break;
                                case "月":
                                case "month":
                                case "months":
                                    time = time.minusMonths(Long.parseLong(numRegex.group(1)));
                                    break;
                                case "日":
                                case "day":
                                case "days":
                                    time = time.minusDays(Long.parseLong(numRegex.group(1)));
                                    break;
                                case "小时":
                                case "hour":
                                case "hours":
                                    time = time.minusHours(Long.parseLong(numRegex.group(1)));
                                    break;
                                case "分钟":
                                case "minute":
                                case "minutes":
                                    time = time.minusMinutes(Long.parseLong(numRegex.group(1)));
                                    break;
                                default:
                                    break;
                            }
                            sr.setYear(time.getYear());
                        }
                    }

                    // 计算分数
                    sr.calculateScore(options);
                    
                    // 设置海报URL
                    String posterUrl = img.attr("src");
                    sr.setPosterUrl(posterUrl);
                    
                    results.add(sr);
                }
            } catch (Exception e) {
                logger.error("Error searching for '{}'", searchString, e);
                throw new ScrapeException(e);
            }
        }

        return results;
    }

    @Override
    public MediaMetadata getMetadata(MovieSearchAndScrapeOptions options) throws ScrapeException {
        logger.debug("getMetadata(): {}", options);
        MediaMetadata md = new MediaMetadata(providerInfo.getId());
        
        String videoId = options.getIdAsString(getId());
        if (StringUtils.isEmpty(videoId)) {
            return null;
        }
        
        try {
            // 构建视频页面URL
            String url = API_URL + "/view_video.php?viewkey=" + videoId;
            Document doc = getDocument(url);
            
            // 设置IMDB ID (TMM内部使用)
            md.setId(MediaMetadata.IMDB, "ph" + videoId);
            
            // 6. 获取影片的ID编号
            md.setId(providerInfo.getId(), videoId);
            
            // 解析标题
            Element titleElement = doc.selectFirst("h1.title");
            if (titleElement != null) {
                String title = titleElement.text().trim();
                md.setTitle(title);
                // 2. 保留原始标题作为英文标题
                md.setOriginalTitle(title);
            }
            
            // 解析缩略图/海报
            Elements scriptElements = doc.select("script");
            String thumbUrl = null;
            for (Element script : scriptElements) {
                String scriptContent = script.html();
                if (scriptContent.contains("image_url")) {
                    // 提取缩略图URL
                    Pattern pattern = Pattern.compile("image_url\\s*:\\s*['\"]([^'\"]+)['\"]");
                    Matcher matcher = pattern.matcher(scriptContent);
                    if (matcher.find()) {
                        thumbUrl = matcher.group(1);
                        break;
                    }
                }
            }
            
            if (thumbUrl != null) {
                MediaArtwork ma = new MediaArtwork(providerInfo.getId(), MediaArtwork.MediaArtworkType.POSTER);
                ma.setPreviewUrl(thumbUrl);
                ma.setDefaultUrl(thumbUrl);
                ma.setOriginalUrl(thumbUrl);
                md.addMediaArt(ma);
            }
            
            // 9. 设置genre为Erotic
            md.addGenre(MediaGenres.EROTIC);
            
            // 1. 更新studio设置的选择器 - 尝试两种可能的选择器
            Element studioElement = doc.selectFirst(".video-detailed-info .video-info-row.userRow > div.userInfoBlock > div.userInfo > div > span > a");
            if (studioElement == null) {
                // 尝试第二种选择器
                studioElement = doc.selectFirst(".video-detailed-info .video-info-row.userRow > div.userInfoBlock > div.userInfo > div > a");
            }
            
            if (studioElement != null) {
                String studio = studioElement.text().trim();
                md.addProductionCompany(studio);
            }
            
            // 3. 更新演员信息选择器 - 首先验证section标题是否包含"Pornstar"
            Elements pornstars = new Elements();
            boolean isPornstarSection = false;
            
            // DEBUG: 在日志中输出页面结构以便诊断问题
            logger.debug("开始提取演员信息...");
            
            // 首先检查演员区域的标题是否包含"Pornstar"
            Element pornstarsSectionTitle = doc.selectFirst("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.js-suggestionsRow.isFirstRow > div.categoriesWrapper > p");
            
            if (pornstarsSectionTitle != null) {
                String titleText = pornstarsSectionTitle.text().trim();
                logger.debug("找到演员区域标题: '{}'", titleText);
                // 检查是否包含"Pornstar"（考虑到可能有空格和特殊字符）
                isPornstarSection = titleText.replace("&nbsp;", " ").trim().toLowerCase().contains("pornstar");
            } else {
                logger.debug("未找到演员区域标题，尝试其他方法检测演员区域");
                // 尝试其他方式检测是否有演员区域 - 例如检查是否有演员链接
                Elements potentialActorLinks = doc.select("a.pstar-list-btn[data-label='pornstar']");
                isPornstarSection = potentialActorLinks != null && !potentialActorLinks.isEmpty();
                
                if (isPornstarSection) {
                    logger.debug("通过演员链接判断存在演员区域");
                }
            }
            
            logger.debug("演员区域检测结果: {}", isPornstarSection ? "找到演员区域" : "未找到演员区域");
            
            // 尝试获取演员信息，无论是否是Pornstar区域
            // 1. 先尝试data-label='pornstar'的通用选择器，这个最可靠
            Elements pornstarLinks = doc.select("a[data-label='pornstar']");
            if (pornstarLinks != null && !pornstarLinks.isEmpty()) {
                logger.debug("通过data-label='pornstar'找到{}个演员", pornstarLinks.size());
                // 过滤掉suggested演员
                for (Element link : pornstarLinks) {
                    if (!link.hasClass("js-suggested")) {
                        pornstars.add(link);
                    }
                }
            }
            
            // 2. 尝试第一个指定的选择器
            if (pornstars.isEmpty()) {
                Elements firstSelectorActors = doc.select("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.js-suggestionsRow.isFirstRow > div > a:not(.js-suggested)");
                if (firstSelectorActors != null && !firstSelectorActors.isEmpty()) {
                    logger.debug("通过第一个选择器找到{}个演员", firstSelectorActors.size());
                    pornstars.addAll(firstSelectorActors);
                }
            }
            
            // 3. 尝试第二个指定的选择器
            if (pornstars.isEmpty()) {
                Elements secondSelectorActors = doc.select("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.js-suggestionsRow.showLess > div > div.tooltipTrig.suggestBtn.preventDropdown > a");
                if (secondSelectorActors != null && !secondSelectorActors.isEmpty()) {
                    logger.debug("通过第二个选择器找到{}个演员", secondSelectorActors.size());
                    // 过滤掉suggested演员
                    for (Element actor : secondSelectorActors) {
                        if (!actor.hasClass("js-suggested")) {
                            pornstars.add(actor);
                        }
                    }
                }
            }
            
            // 4. 尝试更简单的选择器，比如class包含pornstar的链接
            if (pornstars.isEmpty()) {
                Elements simpleSelectors = doc.select("a.pstar-list-btn");
                if (simpleSelectors != null && !simpleSelectors.isEmpty()) {
                    logger.debug("通过简单选择器找到{}个潜在演员", simpleSelectors.size());
                    // 过滤掉suggested演员
                    for (Element actor : simpleSelectors) {
                        if (!actor.hasClass("js-suggested")) {
                            pornstars.add(actor);
                        }
                    }
                }
            }
            
            logger.debug("找到{}个有效演员", pornstars.size());
            
            // 如果没有找到演员，则使用上传者作为演员
            if (pornstars.isEmpty()) {
                logger.debug("没有找到演员，尝试使用上传者");
                // 尝试第一种选择器 - 带span的上传者链接
                Element uploaderElement = doc.selectFirst("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.userRow > div.userInfoBlock > div.userInfo > div > span > a");
                
                // 如果第一种选择器失败，尝试没有span的上传者链接
                if (uploaderElement == null) {
                    uploaderElement = doc.selectFirst("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.userRow > div.userInfoBlock > div.userInfo > div > a");
                }
                
                // 如果仍然没有找到，尝试更简单的选择器
                if (uploaderElement == null) {
                    uploaderElement = doc.selectFirst(".userInfo a.bolded");
                }
                
                if (uploaderElement != null) {
                    logger.debug("找到上传者: {}", uploaderElement.text().trim());
                    pornstars.add(uploaderElement);
                } else {
                    logger.debug("未找到上传者信息");
                }
            }
            
            // 将找到的演员添加到元数据
            if (!pornstars.isEmpty()) {
                for (Element pornstar : pornstars) {
                    String name = pornstar.text().trim();
                    if (!StringUtils.isEmpty(name)) {
                        logger.debug("处理演员: {}", name);
                        Person actor = new Person(Person.Type.ACTOR, name);
                        
                        // 提取演员信息页URL
                        String profileHref = pornstar.attr("href");
                        if (!StringUtils.isEmpty(profileHref)) {
                            // 确保URL是完整的，添加域名如果需要
                            String fullProfileUrl;
                            if (profileHref.startsWith("/")) {
                                fullProfileUrl = "https://www.pornhub.com" + profileHref;
                            } else if (!profileHref.startsWith("http")) {
                                fullProfileUrl = "https://www.pornhub.com/" + profileHref;
                            } else {
                                fullProfileUrl = profileHref;
                            }
                            logger.debug("设置演员资料页URL: {}", fullProfileUrl);
                            actor.setProfileUrl(fullProfileUrl);
                        }
                        
                        // 尝试获取演员头像
                        String actorImg = "";
                        
                        // 1. 首先尝试查找img子元素
                        Element imgElement = pornstar.selectFirst("img.avatar");
                        if (imgElement != null) {
                            actorImg = imgElement.attr("src");
                            logger.debug("从img.avatar获取到演员头像: {}", actorImg);
                        }
                        
                        // 2. 如果没有找到，检查是否有data-thumb_url属性
                        if (StringUtils.isEmpty(actorImg)) {
                            actorImg = pornstar.attr("data-thumb_url");
                            if (StringUtils.isNotEmpty(actorImg)) {
                                logger.debug("从data-thumb_url获取到演员头像: {}", actorImg);
                            }
                        }
                        
                        // 3. 如果仍未找到头像，并且这是上传者，尝试从专门的头像区域获取
                        if (StringUtils.isEmpty(actorImg) && pornstars.size() == 1) {
                            Element uploaderAvatarElement = doc.selectFirst("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-container > div.video-actions-tabs > div.video-action-tab.about-tab.active > div.video-detailed-info > div.video-info-row.userRow > div.userInfoBlock > div.userAvatar > img");
                            if (uploaderAvatarElement == null) {
                                uploaderAvatarElement = doc.selectFirst(".userAvatar img");
                            }
                            
                            if (uploaderAvatarElement != null) {
                                actorImg = uploaderAvatarElement.attr("src");
                                logger.debug("从上传者头像区域获取到演员头像: {}", actorImg);
                            }
                        }
                        
                        if (StringUtils.isNotEmpty(actorImg)) {
                            actor.setThumbUrl(actorImg);
                        } else {
                            logger.debug("未找到演员头像");
                        }
                        
                        md.addCastMember(actor);
                        logger.debug("成功添加演员: {}", name);
                    }
                }
            } else {
                logger.debug("未找到任何演员信息");
            }
            
            // 4. 设置语言（如果有，排除suggested）
            Elements languageElements = doc.select(".video-detailed-info .multiSectionWrapper .langSpokenWrapper a:not(.js-suggested)");
            if (languageElements != null && !languageElements.isEmpty()) {
                List<String> languages = new ArrayList<>();
                for (Element langElement : languageElements) {
                    String language = langElement.text().trim();
                    if (StringUtils.isNotEmpty(language)) {
                        languages.add(language);
                    }
                }
                if (!languages.isEmpty()) {
                    md.setSpokenLanguages(languages);
                }
            }
            
            // 8. 解析添加日期/年份 - 使用多种选择器确保获取到发行日期
            boolean dateFound = false;
            
            // 优先尝试从schema.org元数据中提取日期（最精确的方式）
            Elements ldJsonScripts = doc.select("script[type='application/ld+json']");
            for (Element script : ldJsonScripts) {
                try {
                    String content = script.html().trim();
                    if (content.contains("datePublished")) {
                        Pattern datePattern = Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher dateMatcher = datePattern.matcher(content);
                        if (dateMatcher.find()) {
                            String dateStr = dateMatcher.group(1).trim();
                            logger.debug("从LD+JSON找到日期: {}", dateStr);
                            
                            try {
                                Date parsedDate = org.tinymediamanager.scraper.util.StrgUtils.parseDate(dateStr);
                                if (parsedDate != null) {
                                    Calendar cal = Calendar.getInstance();
                                    cal.setTime(parsedDate);
                                    int year = cal.get(Calendar.YEAR);
                                    
                                    md.setYear(year);
                                    md.setReleaseDate(parsedDate);
                                    logger.debug("成功从JSON设置年份: {} 和发行日期: {}", year, parsedDate);
                                    dateFound = true;
                                    break;
                                }
                            } catch (Exception e) {
                                logger.debug("解析JSON日期失败: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 忽略JSON解析错误
                }
            }
            
            // 从meta标签中查找uploadDate
            if (!dateFound) {
                Element metaUploadDate = doc.selectFirst("meta[property='video:release_date']");
                if (metaUploadDate == null) {
                    metaUploadDate = doc.selectFirst("meta[property='og:video:release_date']");
                }
                
                if (metaUploadDate != null) {
                    String dateStr = metaUploadDate.attr("content").trim();
                    logger.debug("从meta标签找到日期: {}", dateStr);
                    
                    try {
                        Date parsedDate = org.tinymediamanager.scraper.util.StrgUtils.parseDate(dateStr);
                        if (parsedDate != null) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(parsedDate);
                            int year = cal.get(Calendar.YEAR);
                            
                            md.setYear(year);
                            md.setReleaseDate(parsedDate);
                            logger.debug("成功从meta标签设置年份: {} 和发行日期: {}", year, parsedDate);
                            dateFound = true;
                        }
                    } catch (Exception e) {
                        logger.debug("解析meta标签日期失败: {}", e.getMessage());
                    }
                }
            }
            
            // 接下来尝试从页面元素中提取相对时间
            if (!dateFound) {
                // 1. 尝试从videoInfo元素获取
                Element videoInfoElement = doc.selectFirst("#hd-leftColVideoPage > div.topSectionGrid > div.videoWrapModelInfo.original > div > div.video-actions-menu > div.ratingInfo > div.videoInfo");
                if (videoInfoElement == null) {
                    // 尝试简化选择器
                    videoInfoElement = doc.selectFirst("div.videoInfo");
                }
                
                if (videoInfoElement != null) {
                    String relativeTimeText = videoInfoElement.text().trim();
                    logger.debug("找到视频上传时间信息: '{}'", relativeTimeText);
                    
                    // 首先尝试解析精确的日期格式 (如果有)
                    Pattern exactDatePattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
                    Matcher exactDateMatcher = exactDatePattern.matcher(relativeTimeText);
                    if (exactDateMatcher.find()) {
                        int year = Integer.parseInt(exactDateMatcher.group(1));
                        
                        String dateStr = exactDateMatcher.group(0); // 完整的日期字符串
                        try {
                            Date parsedDate = org.tinymediamanager.scraper.util.StrgUtils.parseDate(dateStr);
                            if (parsedDate != null) {
                                md.setYear(year);
                                md.setReleaseDate(parsedDate);
                                logger.debug("从精确日期设置年份: {} 和发行日期: {}", year, parsedDate);
                                dateFound = true;
                            }
                        } catch (Exception e) {
                            logger.warn("无法解析精确的发行日期: {}", dateStr);
                        }
                    } 
                    // 处理相对时间描述（如"1年前", "3个月前", "5天前"等）
                    else {
                        LocalDateTime estimatedDate = null;
                        
                        // 中文相对时间模式
                        Pattern chinesePattern = Pattern.compile("(\\d+)\\s*([年月周天小时分钟秒])前");
                        Matcher chineseMatcher = chinesePattern.matcher(relativeTimeText);
                        if (chineseMatcher.find()) {
                            int amount = Integer.parseInt(chineseMatcher.group(1));
                            String unit = chineseMatcher.group(2);
                            estimatedDate = parseRelativeTime(amount, unit);
                            logger.debug("从中文相对时间解析: {} {}", amount, unit);
                        } 
                        // 英文相对时间模式
                        else {
                            Pattern englishPattern = Pattern.compile("(\\d+)\\s*(year|years|month|months|week|weeks|day|days|hour|hours|minute|minutes|second|seconds)\\s+ago", Pattern.CASE_INSENSITIVE);
                            Matcher englishMatcher = englishPattern.matcher(relativeTimeText);
                            if (englishMatcher.find()) {
                                int amount = Integer.parseInt(englishMatcher.group(1));
                                String unit = englishMatcher.group(2).toLowerCase();
                                estimatedDate = parseEnglishRelativeTime(amount, unit);
                                logger.debug("从英文相对时间解析: {} {}", amount, unit);
                            }
                        }
                        
                        if (estimatedDate != null) {
                            int year = estimatedDate.getYear();
                            Date releaseDate = java.util.Date.from(estimatedDate.atZone(java.time.ZoneId.systemDefault()).toInstant());
                            
                            md.setYear(year);
                            md.setReleaseDate(releaseDate);
                            logger.debug("从相对时间设置年份: {} 和估计日期: {}", year, releaseDate);
                            dateFound = true;
                        } else {
                            logger.debug("无法解析相对时间: '{}'", relativeTimeText);
                        }
                    }
                } else {
                    logger.debug("未找到videoInfo元素");
                }
            }
            
            // 回退到原有的方法 - 使用.userOptionalInfo
            if (!dateFound) {
                Element uploadDate = doc.selectFirst(".userOptionalInfo");
                if (uploadDate != null) {
                    String dateText = uploadDate.text().trim();
                    logger.debug("从userOptionalInfo找到日期信息: '{}'", dateText);
                    Pattern datePattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
                    Matcher dateMatcher = datePattern.matcher(dateText);
                    if (dateMatcher.find()) {
                        int year = Integer.parseInt(dateMatcher.group(1));
                        
                        // 设置发行日期
                        String dateStr = dateMatcher.group(0); // 完整的日期字符串
                        try {
                            Date parsedDate = org.tinymediamanager.scraper.util.StrgUtils.parseDate(dateStr);
                            if (parsedDate != null) {
                                md.setYear(year);
                                md.setReleaseDate(parsedDate);
                                logger.debug("从userOptionalInfo设置年份: {} 和日期: {}", year, parsedDate);
                                dateFound = true;
                            }
                        } catch (Exception e) {
                            logger.warn("无法解析备用发行日期: {}", dateStr);
                        }
                    }
                } else {
                    logger.debug("未找到userOptionalInfo元素");
                }
            }
            
            // 最后的备用选项 - 从标题或URL中尝试提取年份
            if (!dateFound) {
                logger.debug("所有日期提取方法失败，设置当前年份作为回退选项");
                // 至少设置当前年份作为回退选项
                Calendar now = Calendar.getInstance();
                md.setYear(now.get(Calendar.YEAR));
            }
            
            // 解析评分和投票数
            Element ratingPercentElement = doc.selectFirst(".ratingPercent .percent");
            if (ratingPercentElement != null) {
                String ratingText = ratingPercentElement.text().trim();
                try {
                    // 转换百分比为10分制评分（88% -> 8.8）
                    int likesPercent = Integer.parseInt(ratingText.replace("%", ""));
                    float rating = likesPercent / 10.0f;
                    
                    // 获取投票数
                    Element votesElement = doc.selectFirst(".votes-fav-wrap .votesUp");
                    int votes = 0;
                    if (votesElement != null) {
                        String votesText = votesElement.text().trim();
                        // 处理数字后缀（K、M等）
                        if (votesText.endsWith("K")) {
                            votes = (int)(Float.parseFloat(votesText.replace("K", "")) * 1000);
                        } else if (votesText.endsWith("M")) {
                            votes = (int)(Float.parseFloat(votesText.replace("M", "")) * 1000000);
                        } else {
                            try {
                                votes = Integer.parseInt(votesText);
                            } catch (NumberFormatException e) {
                                // 如果还有data-rating属性，尝试从那里获取
                                String dataRating = votesElement.attr("data-rating");
                                if (StringUtils.isNotEmpty(dataRating)) {
                                    votes = Integer.parseInt(dataRating);
                                }
                            }
                        }
                    }
                    
                    MediaRating mediaRating = new MediaRating(providerInfo.getId());
                    mediaRating.setRating(rating);
                    mediaRating.setMaxValue(10);
                    mediaRating.setVotes(votes);
                    md.addRating(mediaRating);
                } catch (NumberFormatException ignored) {
                    // 无法解析评分，忽略
                }
            }
            
            // 4. 解析视频时长（格式：MM:SS）
            Element durationElement = doc.selectFirst(".mgp_controlBarFront .mgp_time .mgp_total");
            if (durationElement != null) {
                String durationStr = durationElement.text().trim();
                try {
                    // 解析时长（格式：35:25 -> 35分钟）
                    String[] parts = durationStr.split(":");
                    if (parts.length == 2) {
                        int minutes = Integer.parseInt(parts[0]);
                        // 可选：如果秒数超过30秒，则向上取整
                        int seconds = Integer.parseInt(parts[1]);
                        if (seconds >= 30) {
                            minutes += 1;
                        }
                        md.setRuntime(minutes);
                    }
                } catch (NumberFormatException ignored) {
                    // 尝试使用元数据中的duration
                    Element metaDurationElement = doc.selectFirst("meta[property='video:duration']");
                    if (metaDurationElement != null) {
                        String metaDurationStr = metaDurationElement.attr("content");
                        try {
                            int durationSeconds = Integer.parseInt(metaDurationStr);
                            md.setRuntime(durationSeconds / 60);
                        } catch (NumberFormatException innerIgnored) {
                            // 无法解析时长，忽略
                        }
                    }
                }
            }
            
            // 5. 设置MPAA分级为NC-17
            md.addCertification(org.tinymediamanager.scraper.entities.MediaCertification.US_NC17);
            
            // 解析视频描述/情节
            Element descriptionElement = doc.selectFirst("meta[name='description']");
            if (descriptionElement != null) {
                String description = descriptionElement.attr("content");
                md.setPlot(description);
                md.setTagline(description);
            }
            
            // 解析预告片/视频URL
            Elements videoScripts = doc.select("script");
            for (Element script : videoScripts) {
                String scriptContent = script.html();
                if (scriptContent.contains("flashvars")) {
                    // 尝试解析视频URL
                    Pattern videoPattern = Pattern.compile("mediaDefinitions\\s*:\\s*(\\[.*?\\])");
                    Matcher videoMatcher = videoPattern.matcher(scriptContent);
                    if (videoMatcher.find()) {
                        String mediaJson = videoMatcher.group(1);
                        // 简单解析，找到最高质量的MP4
                        Pattern qualityPattern = Pattern.compile("\"quality\":\"(\\d+)\".*?\"videoUrl\":\"([^\"]+)\"");
                        Matcher qualityMatcher = qualityPattern.matcher(mediaJson);
                        
                        String bestUrl = null;
                        int bestQuality = 0;
                        
                        while (qualityMatcher.find()) {
                            try {
                                int quality = Integer.parseInt(qualityMatcher.group(1));
                                String videoUrl = qualityMatcher.group(2).replace("\\", "");
                                
                                if (quality > bestQuality) {
                                    bestQuality = quality;
                                    bestUrl = videoUrl;
                                }
                            } catch (NumberFormatException ignored) {
                                // 忽略解析错误
                            }
                        }
                        
                        if (bestUrl != null) {
                            MediaTrailer trailer = new MediaTrailer();
                            trailer.setName(md.getTitle());
                            trailer.setUrl(bestUrl);
                            trailer.setProvider(providerInfo.getId());
                            md.addTrailer(trailer);
                        }
                    }
                    break;
                }
            }
            
        } catch (Exception e) {
            logger.error("Error getting metadata for ID '{}'", videoId, e);
            throw new ScrapeException(e);
        }
        
        return md;
    }
    
    /**
     * 获取文档并使用缓存提高性能
     */
    private Document getDocument(String url) throws IOException {
        InMemoryCachedUrl cachedUrl = urlCache.get(url);
        
        if (cachedUrl == null) {
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
            
            // 使用TMM内置的HTTP客户端，以便应用TMM的代理设置
            cachedUrl = new InMemoryCachedUrl(url);
            cachedUrl.addHeader("User-Agent", userAgent);
            cachedUrl.addHeader("Accept-Language", "en-US,en;q=0.9");
            cachedUrl.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            
            try {
                // 获取文档
                cachedUrl.getInputStream(); // 触发请求
                urlCache.put(url, cachedUrl);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while getting document", e);
            }
        }
        
        try {
            return Jsoup.parse(cachedUrl.getInputStream(), "UTF-8", url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while parsing document", e);
        }
    }
    
    /**
     * 解析中文相对时间字符串并转换为精确日期
     * @param amount 时间数量
     * @param unit 时间单位（年月日等）
     * @return 估计的日期时间
     */
    private LocalDateTime parseRelativeTime(int amount, String unit) {
        LocalDateTime now = LocalDateTime.now();
        switch (unit) {
            case "年":
                return now.minusYears(amount);
            case "月":
                return now.minusMonths(amount);
            case "周":
                return now.minusWeeks(amount);
            case "天":
                return now.minusDays(amount);
            case "小时":
                return now.minusHours(amount);
            case "分钟":
                return now.minusMinutes(amount);
            case "秒":
                return now.minusSeconds(amount);
            default:
                logger.warn("未知的时间单位: {}", unit);
                return null;
        }
    }
    
    /**
     * 解析英文相对时间字符串并转换为精确日期
     * @param amount 时间数量
     * @param unit 时间单位（年月日等）
     * @return 估计的日期时间
     */
    private LocalDateTime parseEnglishRelativeTime(int amount, String unit) {
        LocalDateTime now = LocalDateTime.now();
        if (unit.startsWith("year")) {
            return now.minusYears(amount);
        } else if (unit.startsWith("month")) {
            return now.minusMonths(amount);
        } else if (unit.startsWith("week")) {
            return now.minusWeeks(amount);
        } else if (unit.startsWith("day")) {
            return now.minusDays(amount);
        } else if (unit.startsWith("hour")) {
            return now.minusHours(amount);
        } else if (unit.startsWith("minute")) {
            return now.minusMinutes(amount);
        } else if (unit.startsWith("second")) {
            return now.minusSeconds(amount);
        } else {
            logger.warn("未知的英文时间单位: {}", unit);
            return null;
        }
    }
} 