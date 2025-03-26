package xyz.ifnotnull.tmm.scraper.pornhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.joda.time.DateTime;
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
import org.tinymediamanager.scraper.entities.MediaCertification;
import org.tinymediamanager.scraper.entities.MediaType;
import org.tinymediamanager.scraper.exceptions.ScrapeException;
import org.tinymediamanager.scraper.http.ProxySettings;
import org.tinymediamanager.scraper.interfaces.IMovieMetadataProvider;
import org.tinymediamanager.scraper.util.MetadataUtil;
import xyz.ifnotnull.tmm.scraper.pornhub.dto.LdJson;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PornhubMovieMetadataProvider implements IMovieMetadataProvider {
  public static final  String            ID                      = "pornhub";
  public static final  String            API_HOST                = "pornhub.com";
  public static final  String            API_URL                 = "https://" + API_HOST;
  private static final Logger            logger                  = LoggerFactory.getLogger(PornhubMovieMetadataProvider.class);
  private static final Pattern           ADD_DATE_REGEX          = Pattern.compile("^(\\d+)\\s*(\\S+)\\s*(?:ago|前)$");
  private static final Pattern           THUMB_URL_INDEX_PATTERN = Pattern.compile("\\{(\\d+)}");
  private static final String            CONFIG_ID_MATCHER       = "ID Matcher";
  private static final String            CONFIG_ACCOUNT          = "Pornhub Account";
  private static final String            CONFIG_PASSWORD         = "Pornhub Password";
  private final        ObjectMapper      objectMapper            = new ObjectMapper();
  private final        MediaProviderInfo providerInfo;
  private final        Playwright        playwright;
  private final        Browser           browser;

  public PornhubMovieMetadataProvider() {
    providerInfo = createProviderInfo();
    playwright = Playwright.create();
    // 添加全局代理设置
    BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
        .setHeadless(true)
        // 性能优化：禁用多媒体功能和JavaScript JIT
        .setArgs(Arrays.asList(
            "--disable-gpu",
            "--disable-dev-shm-usage",
            "--disable-setuid-sandbox",
            "--no-sandbox",
            "--disable-accelerated-2d-canvas",
            "--no-first-run",
            "--no-zygote",
            "--disable-remote-fonts",
            "--disable-background-networking"));
    
    // 如果TMM配置了代理，则设置全局代理
    if (StringUtils.isNotEmpty(ProxySettings.INSTANCE.getHost())) {
      launchOptions.setProxy(new Proxy("http://" + ProxySettings.INSTANCE.getHost() + ":" + ProxySettings.INSTANCE.getPort()));
    } else {
      // 设置一个哑代理，确保所有上下文代理能正常工作
      launchOptions.setProxy(new Proxy("http://localhost:0"));
    }
    
    browser = playwright.chromium().launch(launchOptions);
    // 注册关闭钩子
    Runtime.getRuntime().addShutdownHook(new Thread(this::close));
  }

  private MediaProviderInfo createProviderInfo() {
    MediaProviderInfo info = new MediaProviderInfo(ID, "movie", "Pornhub", "Scraper addon for Pornhub",
        PornhubMovieMetadataProvider.class.getResource("/xyz/ifnotnull/tmm/scraper/pornhub/pornhub_logo.svg"));
    // the ResourceBundle to offer i18n support for scraper options
    info.setResourceBundle(ResourceBundle.getBundle("xyz.ifnotnull.tmm.scraper.pornhub.messages"));

    // create configuration properties
    info.getConfig().addText(CONFIG_ID_MATCHER, "^(\\w+?)\\s*[|!@].*", false);
    info.getConfig().addText(CONFIG_ACCOUNT, "", false);
    info.getConfig().addText(CONFIG_PASSWORD, "", true);

    /*info.getConfig().addBoolean("boolean", true);
    info.getConfig().addInteger("integer", 10);
    info.getConfig().addSelect("select", new String[] { "A", "B", "C" }, "A");*/

    // load any existing values from the storage
    info.getConfig().load();

    return info;
  }

  private void close() {
    logger.info("PornhubMovieMetadataProvider closing...");
    // 关闭资源
    browser.close();
    playwright.close();
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

    // we hope got an id from options but not
    if (StringUtils.isEmpty(phId)) {
      // try if filename contains an id
      String idMatcher = getProviderInfo().getConfig().getValue(CONFIG_ID_MATCHER);
      Matcher matcher = Pattern.compile(idMatcher).matcher(options.getSearchQuery());
      if (matcher.matches()) {
        phId = matcher.group(1);
      }
    }

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

    // no id yet, search via filename
    String searchString = MetadataUtil.removeNonSearchCharacters(options.getSearchQuery());
    if (StringUtils.isNotEmpty(searchString)) {
      synchronized (browser) {
        try (Page page = browser.newPage(new Browser.NewPageOptions()
            .setLocale(options.getLanguage().getLanguage())
            .setProxy(StringUtils.isNotEmpty(ProxySettings.INSTANCE.getHost()) 
                ? new Proxy(ProxySettings.INSTANCE.getHost() + ":" + ProxySettings.INSTANCE.getPort())
                : null))) {
          page.navigate(API_URL);

          //          Locator searchBar = page.getByPlaceholder("Search Pornhub");
          Locator searchBar = page.locator("#searchInput");
          searchBar.fill(searchString);
          searchBar.press("Enter");

          page.waitForLoadState();

          // find result list element
          List<Locator> searchResults = page.locator("#videoSearchResult")
              .getByRole(AriaRole.LISTITEM)
              .filter(new Locator.FilterOptions().setHas(page.getByRole(AriaRole.IMG)))
              .all();
          
          // 限制结果数量为前8个
          int maxResults = Math.min(8, searchResults.size());
          for (int i = 0; i < maxResults; i++) {
            Locator result = searchResults.get(i);
            Locator img = result.getByRole(AriaRole.IMG);
            String title = img.getAttribute("data-title");

            MediaSearchResult sr = new MediaSearchResult(getId(), MediaType.MOVIE);
            // 设置标准ID
            String videoKey = result.getAttribute("data-video-vkey");
            String videoId = result.getAttribute("data-video-id");
            sr.setId(getId(), videoKey);
            sr.setId(getId() + "_id", videoId);
            // 添加IMDB ID (TMM内部使用)
            sr.setId(MediaMetadata.IMDB, "ph" + videoId);
            
            sr.setTitle(title);
            sr.setOriginalTitle(title);

            String addedDate = result.locator(".added").textContent().trim();
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

            // calculate score self
            sr.calculateScore(options);
            
            // 确保设置海报URL
            String posterUrl = img.getAttribute("src");
            sr.setPosterUrl(posterUrl);
            
            // MPAA评级和genre只能在metadata对象上设置，不能在MediaSearchResult上设置
            
            // 设置overview
            String overview = "Adult video from Pornhub. ID: " + videoKey;
            sr.setOverview(overview);
            
            results.add(sr);
          }
        }
      }
    }

    return results;
  }

  @Override
  public MediaMetadata getMetadata(MovieSearchAndScrapeOptions options) throws ScrapeException {
    logger.debug("getMetadata(): {}", options);
    if (options.getSearchResult() != null && options.getSearchResult().getMediaMetadata() != null && getId().equals(
        options.getSearchResult().getMediaMetadata().getProviderId())) {
      return options.getSearchResult().getMediaMetadata();
    }

    // 创建新的元数据对象并保存选项 - 关键部分确保TMM知道这是电影元数据
    MediaMetadata md = new MediaMetadata(getId());
    md.setScrapeOptions(options);
    
    // MediaMetadata不需要显式设置媒体类型，它是从IMovieMetadataProvider继承的
    
    String phId = options.getIdAsString(getId());
    synchronized (browser) {
      try (Page page = browser.newPage(new Browser.NewPageOptions()
          .setLocale(options.getLanguage().getLanguage())
          .setProxy(StringUtils.isNotEmpty(ProxySettings.INSTANCE.getHost()) 
              ? new Proxy(ProxySettings.INSTANCE.getHost() + ":" + ProxySettings.INSTANCE.getPort())
              : null))) {
        
        // 性能优化：配置页面参数
        page.setDefaultTimeout(30000); // 设置超时为30秒
        page.setDefaultNavigationTimeout(30000); // 导航超时30秒
        
        // 性能优化：屏蔽不必要的资源
        page.route("**/*.{png,jpg,jpeg,gif,webp}", route -> route.resume()); // 允许图片但不阻塞
        page.route("**/*.{css,woff,woff2,ttf,otf,svg}", route -> route.resume()); // 允许CSS和字体但不阻塞
        page.route("**/*.{mp4,webm,ogg,avi,mov}", route -> route.abort()); // 阻止视频加载
        
        // 设置页面请求拦截器
        page.onResponse(response -> {
          if (response.status() > 400) {
            logger.warn("Response {}:{}, url: {}", response.status(), response.statusText(), response.url());
            throw new PlaywrightException("response code: " + response.status());
          }
        });
        
        String url = API_URL + "/" + "view_video.php?viewkey=" + phId;
        logger.info("Navigating to URL: {}", url);
        page.navigate(url);
        
        // 性能优化：只等待DOM和网络稳定，不等待图片等加载完成
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        
        logger.info("Page loaded, DOM content ready");
        
        // 尝试等待关键元素出现，而不是等待整个页面加载完成
        try {
          page.waitForSelector("#player", new Page.WaitForSelectorOptions().setTimeout(10000));
          logger.info("Player element found");
        } catch (Exception e) {
          logger.warn("Timeout waiting for player element, continuing anyway: {}", e.getMessage());
        }

        // 设置ID - 必须保存
        md.setId(getId(), options.getIdAsString(getId()));
        String videoId = page.locator("#player").getAttribute("data-video-id"); 
        if (StringUtils.isNotEmpty(videoId)) {
          md.setId(getId() + "_id", videoId);
          // 同时设置TMM内部ID - 这很重要
          md.setId(MediaMetadata.IMDB, "ph" + videoId);
        }
        
        try {
          parseLdJson(md, page, options);
          parseFlashvars(md, page, options);
          parseVideoShow(md, page, options);
          parsePageElements(md, page);
          
          // 确保所有必要的数据都设置完成
          finalizeMetadata(md);
        }
        catch (JsonProcessingException e) {
          logger.error("parse error", e);
          return null;
        }
      }
    }

    return md;
  }

  /**
   * 最终确认所有元数据已正确设置
   */
  private void finalizeMetadata(MediaMetadata md) {
    // 确保有标题，如果没有则使用"Unknown"
    if (StringUtils.isEmpty(md.getTitle())) {
      md.setTitle("Unknown");
      logger.info("Setting default title 'Unknown'");
    }
    
    // 确保有originalTitle，如果没有则使用title
    if (StringUtils.isEmpty(md.getOriginalTitle())) {
      md.setOriginalTitle(md.getTitle());
      logger.info("Setting originalTitle to match title: {}", md.getTitle());
    }
    
    // 确保有发行年份，没有则使用当前年份
    if (md.getYear() == 0) {
      md.setYear(Calendar.getInstance().get(Calendar.YEAR));
      logger.info("Setting current year as fallback: {}", md.getYear());
    }
    
    // 确保有plot
    if (StringUtils.isEmpty(md.getPlot())) {
      md.setPlot("");
    }
    
    // 设置唯一标识符，确保TMM能识别
    for (Map.Entry<String, Object> entry : md.getIds().entrySet()) {
      if (entry.getKey().equals(getId()) || entry.getKey().equals(getId() + "_id")) {
        // 确保TMM内部ID存在，这对某些功能很重要
        if (!md.getIds().containsKey(MediaMetadata.IMDB)) {
          md.setId(MediaMetadata.IMDB, "ph" + entry.getValue());
          logger.info("Set IMDB ID fallback: ph{}", entry.getValue());
        }
      }
    }
    
    // 确保至少有一个评分
    if (md.getRatings().isEmpty()) {
      md.addRating(new MediaRating(MediaRating.USER, 0, 0));
      logger.info("Added empty default rating");
    }
    
    // 确保设置了播放时间
    if (md.getRuntime() <= 0) {
      // 默认设为0，TMM会接受这个值
      md.setRuntime(0);
      logger.info("Set default runtime to 0");
    }
    
    // 确保至少有一个genre
    if (md.getGenres().isEmpty()) {
      md.addGenre(MediaGenres.EROTIC);
      logger.info("Added default genre: Erotic");
    }
    
    // 如果没有制作公司，添加一个默认的
    if (md.getProductionCompanies().isEmpty()) {
      md.addProductionCompany("Unknown Studio");
      logger.info("Added default production company: Unknown Studio");
    }
    
    // 确保所有必要的演员都添加了
    if (md.getCastMembers(Person.Type.ACTOR).isEmpty()) {
      logger.info("No actors found, using defaults");
      String defaultStudio = !md.getProductionCompanies().isEmpty() ? 
          md.getProductionCompanies().get(0) : "Unknown";
      
      Person actor = new Person(Person.Type.ACTOR, defaultStudio);
      md.addCastMember(actor);
      logger.info("Added default actor: {}", defaultStudio);
    }
    
    logger.info("Metadata finalization complete");
  }

  private void parseVideoShow(MediaMetadata md, Page page, MovieSearchAndScrapeOptions options) {
    Map<String, Object> videoShow = (Map<String, Object>) page.evaluate("VIDEO_SHOW");
    
    logger.info("Parsing VIDEO_SHOW data");
    
    md.setTitle((String) videoShow.get("videoTitleTranslated"));
    logger.info("Set title: {}", videoShow.get("videoTitleTranslated"));
    
    // 确保originalTitle为英文标题
    String originalTitle = (String) videoShow.get("videoTitleOriginal");
    if (StringUtils.isNotEmpty(originalTitle)) {
      md.setOriginalTitle(originalTitle);
      logger.info("Set originalTitle to English title: {}", originalTitle);
    } else {
      md.setOriginalTitle((String) videoShow.get("videoTitleTranslated"));
      logger.info("originalTitle not found, using translated title as fallback");
    }
    
    // 确保设置了原始语言 - 明确使用setOriginalLanguage方法
    md.setOriginalLanguage("en");
    logger.info("Set originalLanguage to: en");
    
    // 设置uniqueid
    String videoId = (String) videoShow.get("videoId");
    if (StringUtils.isNotEmpty(videoId)) {
      md.setId("pornhub_id", videoId);
      // 同时设置TMM内部ID
      md.setId(MediaMetadata.IMDB, "ph" + videoId);
      logger.info("Set pornhub_id uniqueid: {}", videoId);
    }
  }

  private void parseLdJson(MediaMetadata md, Page page, MovieSearchAndScrapeOptions options) throws JsonProcessingException {
    String jsonContent = page.innerHTML("script[type=\"application/ld+json\"]");
    logger.info("Found LD+JSON content: {}", StringUtils.abbreviate(jsonContent, 100));
    
    LdJson ldJson = objectMapper.readValue(jsonContent, LdJson.class);

    if (ldJson == null) {
      logger.warn("LD+JSON parsing returned null object");
      return;
    }
    
    String plot = StringEscapeUtils.unescapeXml(ldJson.getDescription()).replace("&period;", ".").replace("&comma;", ",");
    md.setPlot(plot);
    logger.info("Set plot: {}", StringUtils.abbreviate(plot, 50));

    // 设置发布日期
    if (StringUtils.isNotEmpty(ldJson.getUploadDate())) {
      try {
        DateTime uploadDate = DateTime.parse(ldJson.getUploadDate());
        md.setYear(uploadDate.getYear());
        md.setReleaseDate(uploadDate);
        // 确保使用TMM认可的方式设置发布日期
        try {
          java.util.Date date = uploadDate.toDate();
          md.setReleaseDate(date);
        } catch (Exception e) {
          logger.warn("Error converting DateTime to Date: {}", e.getMessage());
        }
        logger.info("Set release date: {}, year: {}", ldJson.getUploadDate(), uploadDate.getYear());
      } catch (Exception e) {
        logger.warn("Failed to parse upload date: {}", ldJson.getUploadDate(), e);
      }
    }
    
    // 设置uniqueid
    if (StringUtils.isNotEmpty(ldJson.getUrl())) {
      String url = ldJson.getUrl();
      // 从URL中提取视频ID
      Pattern pattern = Pattern.compile("viewkey=(\\w+)");
      Matcher matcher = pattern.matcher(url);
      if (matcher.find()) {
        String videoId = matcher.group(1);
        md.setId("pornhub", videoId);
        logger.info("Set pornhub uniqueid from URL: {}", videoId);
      }
    }

    // poster
    String imgUrl = ldJson.getThumbnailUrl();
    logger.info("Found poster URL: {}", imgUrl);

    MediaArtwork poster = new MediaArtwork(getId(), MediaArtwork.MediaArtworkType.POSTER);
    poster.setLanguage(options.getLanguage().getLanguage());
    poster.setDefaultUrl(imgUrl);
    poster.setPreviewUrl(imgUrl);
    poster.setOriginalUrl(imgUrl);
    poster.setSizeOrder(8);
    md.addMediaArt(poster);
    logger.info("Added poster artwork");

    // extra thumbs
    //    Pattern pattern = Pattern.compile("^(.*)(\\d+)\\.jpg$");
    Pattern pattern = Pattern.compile("(?<=\\D)(\\d+)(?=\\.jpg)");
    // https://ei.phncdn.com/videos/202211/06/418983331/original/(m=qZ6-V2XbeWdTGgaaaa)(mh=ax-Hrw-9ZtGX9IIr)0.jpg 将末尾的0.jpg替换为i.jpg
    Matcher matcher = pattern.matcher(imgUrl);
    if (matcher.find()) {
      for (int i = 0; i <= 16; i++) {
        String newUrl = matcher.replaceAll(String.valueOf(i));
        MediaArtwork fanArt = new MediaArtwork(getId(), MediaArtwork.MediaArtworkType.BACKGROUND);
        fanArt.setLanguage(options.getLanguage().getLanguage());
        fanArt.setDefaultUrl(newUrl);
        fanArt.setPreviewUrl(newUrl);
        fanArt.setOriginalUrl(newUrl);
        fanArt.setSizeOrder(8);
        md.addMediaArt(fanArt);
      }
    }
  }

  private void parseFlashvars(MediaMetadata md, Page page, MovieSearchAndScrapeOptions options) {
    String videoId = options.getIdAsString(getId() + "_id");
    String urlPattern = (String) page.evaluate("flashvars_" + videoId + ".thumbs.urlPattern");

    Matcher matcher = THUMB_URL_INDEX_PATTERN.matcher(urlPattern);
    if (matcher.find()) {
      int thumbsCount = Integer.parseInt(matcher.group(1)) + 1;
      List<MediaArtwork> thumbs = IntStream.range(0, thumbsCount).mapToObj(idx -> {
        String url = matcher.replaceAll(String.valueOf(idx));
        MediaArtwork artwork = new MediaArtwork(getId(), MediaArtwork.MediaArtworkType.BACKGROUND);
        artwork.setLanguage(options.getLanguage().getLanguage());
        artwork.setDefaultUrl(url);
        artwork.setPreviewUrl(url);
        artwork.setOriginalUrl(url);
        artwork.setSizeOrder(8);
        return artwork;
      }).collect(Collectors.toList());
      md.addMediaArt(thumbs);
    }
  }

  private void parsePageElements(MediaMetadata md, Page page) {
    logger.info("Starting to parse page elements");
    Locator videoLocator = page.locator("#hd-leftColVideoPage");

    // 设置mpaa和certification为NC-17 - 确保使用TMM认可的方式
    MediaCertification certification = MediaCertification.US_NC17;
    md.addCertification(certification);
    // 同时使用标准字段保存
    md.setCertifications(Collections.singletonList(certification));
    logger.info("Set certification to US_NC17");
    
    // 通过addTag添加MPAA信息
    md.addTag("MPAA: " + MediaCertification.US_NC17.name());
    logger.info("Added MPAA tag");

    // 解析评分信息，使用百分比转换为10分制
    try {
      // 从百分比获取评分
      String percentText = videoLocator.locator("div.ratingInfo div.ratingPercent span.percent").textContent().trim();
      logger.info("Found rating percent text: {}", percentText);
      
      if (percentText != null && percentText.endsWith("%")) {
        int percent = Integer.parseInt(percentText.replace("%", ""));
        float rating = (float) percent / 10.0f;  // 转换为10分制
        
        // 获取投票数
        String votesText = videoLocator.locator("div.votes-fav-wrap div.js-voteUp span.votesUp").getAttribute("data-rating");
        int votes = 0;
        if (StringUtils.isNotEmpty(votesText)) {
          votes = Integer.parseInt(votesText);
          logger.info("Found votes from data-rating: {}", votes);
        } else {
          // 尝试从显示的文本中解析，如"3K"
          votesText = videoLocator.locator("div.votes-fav-wrap div.js-voteUp span.votesUp").textContent().trim();
          logger.info("Trying to parse votes from text: {}", votesText);
          
          if (votesText.endsWith("K")) {
            float num = Float.parseFloat(votesText.replace("K", ""));
            votes = (int) (num * 1000);
            logger.info("Parsed K votes: {}", votes);
          } else {
            try {
              votes = Integer.parseInt(votesText);
              logger.info("Parsed numeric votes: {}", votes);
            } catch (NumberFormatException e) {
              logger.warn("Failed to parse votes text: {}", votesText);
            }
          }
        }
        md.addRating(new MediaRating(MediaRating.USER, rating, votes, 10));
        // 不要使用setRating和setVoteCount，只使用addRating
        logger.info("Added user rating: {} with {} votes", rating, votes);
      } else {
        logger.info("Using fallback rating calculation");
        // 使用原有的评分逻辑作为备选
        int currentUp = Integer.parseInt(videoLocator.locator("[data-rating]").and(videoLocator.locator(".votesUp")).getAttribute("data-rating"));
        int currentDown = Integer.parseInt(videoLocator.locator("[data-rating]").and(videoLocator.locator(".votesDown")).getAttribute("data-rating"));
        int totalVotes = currentUp + currentDown;
        float rating = 10.0f * currentUp / totalVotes;
        md.addRating(new MediaRating(MediaRating.USER, rating, totalVotes, 10));
        // 不要使用setRating和setVoteCount，只使用addRating
        logger.info("Added fallback rating: {} with {} votes", rating, totalVotes);
      }
    } catch (Exception e) {
      logger.warn("Error parsing rating: {}", e.getMessage());
      // 出错时使用原有的评分逻辑
      try {
        int currentUp = Integer.parseInt(videoLocator.locator("[data-rating]").and(videoLocator.locator(".votesUp")).getAttribute("data-rating"));
        int currentDown = Integer.parseInt(videoLocator.locator("[data-rating]").and(videoLocator.locator(".votesDown")).getAttribute("data-rating"));
        int totalVotes = currentUp + currentDown;
        float rating = 10.0f * currentUp / totalVotes;
        md.addRating(new MediaRating(MediaRating.USER, rating, totalVotes, 10));
        // 不要使用setRating和setVoteCount，只使用addRating
        logger.info("Added emergency fallback rating: {} with {} votes", rating, totalVotes);
      } catch (Exception ex) {
        logger.warn("Error using fallback rating calculation: {}", ex.getMessage());
      }
    }

    // 解析播放时长
    try {
      String durationText = page.locator("div.mgp_controlBar span.mgp_total").textContent().trim();
      logger.info("Found duration text: {}", durationText);
      
      if (StringUtils.isNotEmpty(durationText)) {
        String[] parts = durationText.split(":");
        int minutes = 0;
        if (parts.length == 2) {
          // 格式: MM:SS
          minutes = Integer.parseInt(parts[0]);
          logger.info("Parsed MM:SS format, minutes: {}", minutes);
        } else if (parts.length == 3) {
          // 格式: HH:MM:SS
          minutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
          logger.info("Parsed HH:MM:SS format, minutes: {}", minutes);
        }
        md.setRuntime(minutes);
        logger.info("Set runtime to {} minutes", minutes);
      }
    } catch (Exception e) {
      logger.warn("Error parsing runtime: {}", e.getMessage());
    }

    // parse trailer url
    try {
      ElementHandle addToTabImg = page.querySelector("div.add-to-tab img");
      if (addToTabImg != null) {
        String trailerUrl = addToTabImg.getAttribute("data-mediabook");
        MediaTrailer trailer = new MediaTrailer();
        trailer.setProvider(getId());
        trailer.setName("mediabook");
        trailer.setUrl(trailerUrl);
        md.addTrailer(trailer);
        logger.info("Added trailer: {}", trailerUrl);
      } else {
        logger.info("No trailer found");
      }
    } catch (Exception e) {
      logger.warn("Error parsing trailer: {}", e.getMessage());
    }

    // parse detail info
    Locator aboutTab = videoLocator.locator("div.about-tab");
    
    // 获取语言信息 - 确保正确设置
    try {
      Locator languageLocator = aboutTab.locator("div.langSpokenWrapper a").first();
      if (languageLocator != null) {
        String language = languageLocator.textContent().trim();
        if (StringUtils.isNotEmpty(language)) {
          md.addSpokenLanguage(language);
          // 如果是英语或默认语言，同时设置为原始语言
          if (language.equalsIgnoreCase("english")) {
            md.setOriginalLanguage("en");
            logger.info("Set original language to English based on spoken language");
          }
          logger.info("Added spoken language: {}", language);
        }
      } else {
        logger.info("No language information found");
      }
    } catch (Exception e) {
      logger.warn("Error parsing language: {}", e.getMessage());
    }
    
    // 解析上传者/制作公司信息
    try {
      // 尝试多种选择器来获取上传者信息
      Locator userRow = aboutTab.locator("div.userRow").first();
      if (userRow == null) {
        userRow = aboutTab.locator("div.usernameWrap").first();
        logger.info("Using alternate userRow selector");
      }
      
      String studio = "";
      String uploaderUrl = "";
      String uploaderThumb = "";
      
      if (userRow != null) {
        // 尝试不同的选择器定位用户名
        try {
          Locator usernameLocator = userRow.locator("span.usernameBadgesWrapper a").first();
          if (usernameLocator != null) {
            studio = usernameLocator.textContent().trim();
            uploaderUrl = usernameLocator.getAttribute("href");
            logger.info("Found studio from primary selector: {}", studio);
          }
        } catch (Exception e) {
          logger.debug("Error with first username selector: {}", e.getMessage());
        }
        
        // 备选选择器
        if (StringUtils.isEmpty(studio)) {
          try {
            Locator altUsernameLocator = userRow.locator("a.bolded").first();
            if (altUsernameLocator != null) {
              studio = altUsernameLocator.textContent().trim();
              uploaderUrl = altUsernameLocator.getAttribute("href");
              logger.info("Found studio from secondary selector: {}", studio);
            }
          } catch (Exception e) {
            logger.debug("Error with alternate username selector: {}", e.getMessage());
          }
        }
        
        // 再尝试其他可能的选择器
        if (StringUtils.isEmpty(studio)) {
          try {
            studio = userRow.textContent().trim();
            logger.info("Found studio from fallback text content: {}", studio);
          } catch (Exception e) {
            logger.debug("Error with fallback text content: {}", e.getMessage());
          }
        }
        
        // 尝试获取上传者头像
        try {
          Locator imgLocator = userRow.getByRole(AriaRole.IMG).first();
          if (imgLocator != null) {
            uploaderThumb = imgLocator.getAttribute("src");
            logger.info("Found uploader thumbnail: {}", uploaderThumb);
          }
        } catch (Exception e) {
          logger.debug("Error getting uploader thumbnail: {}", e.getMessage());
        }
      } else {
        logger.info("No userRow found");
      }
      
      // 如果以上方法都失败，尝试直接从页面获取
      if (StringUtils.isEmpty(studio)) {
        try {
          Locator uploaderAlt = page.locator("div.video-detailed-info a.bolded").first();
          if (uploaderAlt != null) {
            studio = uploaderAlt.textContent().trim();
            uploaderUrl = uploaderAlt.getAttribute("href");
            logger.info("Found studio from final fallback selector: {}", studio);
          }
        } catch (Exception e) {
          logger.debug("Error with final uploader selector: {}", e.getMessage());
        }
      }
      
      // 添加到元数据
      if (StringUtils.isNotEmpty(studio)) {
        // 使用addProductionCompany方法设置制作公司
        md.addProductionCompany(studio);
        // 不要使用setProductionCompany方法
        logger.info("Added production company: {}", studio);
        
        // 修复URL，确保前缀正确
        if (StringUtils.isNotEmpty(uploaderUrl) && !uploaderUrl.startsWith("http")) {
          uploaderUrl = API_URL + uploaderUrl;
          logger.info("Fixed uploader URL: {}", uploaderUrl);
        }
        
        // 创建导演、编剧和制作人
        Person author = new Person(Person.Type.DIRECTOR, studio);
        if (StringUtils.isNotEmpty(uploaderThumb)) {
          author.setThumbUrl(uploaderThumb);
        }
        if (StringUtils.isNotEmpty(uploaderUrl)) {
          author.setProfileUrl(uploaderUrl);
        }
        md.addCastMember(author);
        logger.info("Added director: {}", studio);

        Person writer = new Person(author);
        writer.setType(Person.Type.WRITER);
        md.addCastMember(writer);
        logger.info("Added writer: {}", studio);

        Person producer = new Person(author);
        producer.setType(Person.Type.PRODUCER);
        md.addCastMember(producer);
        logger.info("Added producer: {}", studio);
        
        // 保存上传者用于后备演员
        if (StringUtils.isEmpty(uploaderThumb)) {
          uploaderThumb = "";
        }
        
        // 解析演员信息
        List<Person> actors = new ArrayList<>();
        
        // 首先尝试使用data-label='Pornstar'选择器
        try {
          List<Locator> pornstars = aboutTab.locator("[data-label='Pornstar']").all();
          logger.info("Found {} pornstars with primary selector", pornstars.size());
          
          for (Locator actor : pornstars) {
            String name = "";
            String thumbUrl = "";
            String profileUrl = "";
            
            try {
              name = actor.textContent().trim();
              
              Locator img = actor.getByRole(AriaRole.IMG).first();
              if (img != null) {
                thumbUrl = img.getAttribute("src");
              }
              
              profileUrl = actor.getAttribute("href");
              if (StringUtils.isNotEmpty(profileUrl) && !profileUrl.startsWith("http")) {
                profileUrl = API_URL + profileUrl;
              }
              
              if (StringUtils.isNotEmpty(name)) {
                Person person = new Person(Person.Type.ACTOR, name);
                person.setName(name);
                if (StringUtils.isNotEmpty(thumbUrl)) {
                  person.setThumbUrl(thumbUrl);
                }
                if (StringUtils.isNotEmpty(profileUrl)) {
                  person.setProfileUrl(profileUrl);
                }
                actors.add(person);
                md.addCastMember(person);
                logger.info("Added actor: {} with thumb: {}", name, thumbUrl);
              }
            } catch (Exception e) {
              logger.warn("Error parsing actor details: {}", e.getMessage());
            }
          }
        } catch (Exception e) {
          logger.warn("Error parsing pornstars: {}", e.getMessage());
        }
        
        // 尝试其他可能的演员选择器
        if (actors.isEmpty()) {
          try {
            List<Locator> altPornstars = aboutTab.locator("a.pstar-list-btn").all();
            logger.info("Found {} pornstars with secondary selector", altPornstars.size());
            
            for (Locator actor : altPornstars) {
              try {
                String name = actor.textContent().trim();
                String profileUrl = actor.getAttribute("href");
                
                if (StringUtils.isNotEmpty(profileUrl) && !profileUrl.startsWith("http")) {
                  profileUrl = API_URL + profileUrl;
                }
                
                if (StringUtils.isNotEmpty(name)) {
                  Person person = new Person(Person.Type.ACTOR, name);
                  person.setName(name);
                  if (StringUtils.isNotEmpty(profileUrl)) {
                    person.setProfileUrl(profileUrl);
                  }
                  actors.add(person);
                  md.addCastMember(person);
                  logger.info("Added actor from alternate selector: {}", name);
                }
              } catch (Exception e) {
                logger.warn("Error parsing alternate actor: {}", e.getMessage());
              }
            }
          } catch (Exception e) {
            logger.warn("Error with alternate actor selector: {}", e.getMessage());
          }
        }
        
        // 如果没有找到演员，使用上传者作为演员
        if (actors.isEmpty()) {
          Person person = new Person(Person.Type.ACTOR, studio);
          if (StringUtils.isNotEmpty(uploaderThumb)) {
            person.setThumbUrl(uploaderThumb);
          }
          if (StringUtils.isNotEmpty(uploaderUrl)) {
            person.setProfileUrl(uploaderUrl);
          }
          md.addCastMember(person);
          logger.info("Added uploader as actor: {}", studio);
        }
      } else {
        logger.warn("No studio/uploader information found");
      }
    } catch (Exception e) {
      logger.warn("Error parsing studio and actors: {}", e.getMessage());
    }

    // 设置genre为Erotic
    md.addGenre(MediaGenres.EROTIC);
    logger.info("Added default genre: Erotic");
    
    // 添加其他类别信息
    try {
      List<Locator> categories = aboutTab.locator("[data-label='Category']").all();
      logger.info("Found {} categories", categories.size());
      
      for (Locator category : categories) {
        try {
          String categoryText = category.textContent().trim();
          MediaGenres genre = MediaGenres.getGenre(categoryText);
          if (genre != null) {
            md.addGenre(genre);
            logger.info("Added genre from category: {}", genre);
          } else {
            logger.info("No matching genre for category: {}", categoryText);
          }
        } catch (Exception e) {
          logger.warn("Error processing category: {}", e.getMessage());
        }
      }
    } catch (Exception e) {
      logger.warn("Error parsing categories: {}", e.getMessage());
    }
    
    // 添加标签信息
    try {
      List<Locator> tags = aboutTab.locator("[data-label='Tag']").all();
      logger.info("Found {} tags", tags.size());
      
      for (Locator tag : tags) {
        try {
          String tagText = tag.textContent().trim();
          md.addTag(tagText);
          logger.info("Added tag: {}", tagText);
        } catch (Exception e) {
          logger.warn("Error parsing tag: {}", e.getMessage());
        }
      }
    } catch (Exception e) {
      logger.warn("Error accessing tags: {}", e.getMessage());
    }
    
    logger.info("Finished parsing page elements with metadata: {}", md);
  }

}

