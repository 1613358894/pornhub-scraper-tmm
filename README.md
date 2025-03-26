# Pornhub Scraper Addon for TinyMediaManager

This is a scraper addon for [TinyMediaManager (TMM)](https://www.tinymediamanager.org/) that provides metadata extraction from Pornhub.

## Features

- Extracts metadata from Pornhub videos: title, studio, actors, release date, runtime, etc.
- Retrieves actor information including profile URLs and thumbnails
- Provides proper handling of release dates and years
- Extracts video posters/thumbnails
- Language detection and extraction

## Installation

1. Download the latest release JAR file from the [Releases](https://github.com/JudeThomasZeng/pornhub-scraper/releases) page
2. Place the JAR file in the `plugins` directory of your TinyMediaManager installation
3. Restart TinyMediaManager
4. Enable the Pornhub scraper in TMM's settings

## Known Limitations

1. For performance reasons, the search results are limited to the first 8 items. Future versions may remove this limitation.
2. The scraper currently does not allow selecting the scraping language - it uses the default language settings.

## Usage

### Filename Format Recommendation

For optimal matching, it's recommended to use filenames with the Pornhub video ID, such as:

```
My Video Title [ph123456].mp4
```

Where `123456` is the Pornhub video ID from the URL (viewkey parameter).

### Manual Search

You can also manually search for videos using the TMM search interface.

## Building from Source

To build the scraper from source:

1. Clone this repository
2. Run `mvn clean package`
3. The compiled JAR file will be in the `target` directory

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

Created by JudeThomasZeng