/*
 * Copyright 2012 - 2020 Manuel Laggner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.ifnotnull.tmm.scraper.pornhub;

import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.scraper.interfaces.IMediaProvider;
import org.tinymediamanager.scraper.spi.IAddonProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 此类是TMM插件系统的入口点
 * 通过MetaInfServices注解向TMM注册
 */
@MetaInfServices(IAddonProvider.class)
public class PornhubAddonProvider implements IAddonProvider {
  private static final Logger logger = LoggerFactory.getLogger(PornhubAddonProvider.class);
  // 定义版本号，方便调试
  public static final String VERSION = "1.0";

  public PornhubAddonProvider() {
    logger.info("******** Initializing PornhubAddonProvider v{} ********", VERSION);
  }

  @Override
  public List<Class<? extends IMediaProvider>> getAddonClasses() {
    logger.info("Loading PornhubAddonProvider addon classes v{}", VERSION);
    List<Class<? extends IMediaProvider>> addons = new ArrayList<>();

    // 添加Jsoup实现的元数据提供者（推荐）
    addons.add(JsoupPornhubScraper.class);
    
    // 添加艺术品提供者
    addons.add(PornhubMovieArtworkProvider.class);
    
    // 注意：仍保留旧版实现，但不注册它，避免重复
    // addons.add(PornhubMovieMetadataProvider.class);
    
    logger.info("Loaded {} addon classes successfully for TMM v4.3+", addons.size());
    return addons;
  }
}
