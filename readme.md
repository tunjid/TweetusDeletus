## Tweetus Deletus

A kotlin script for deleting your tweets from your twitter archive downloads.

## Requirements

Install Kotlin on the command line by following the instructions [here](https://kotlinlang.org/docs/command-line.html).

## Steps:

1. If you do not have a Twitter developer account/app, create one by following the instructions [here](https://developer.twitter.com/en/docs/apps/overview)
   
1. Clone this repository

2. Download your Twitter archive by following the instructions [here](https://help.twitter.com/en/managing-your-account/how-to-download-your-twitter-archive)

3. Unzip the archive file and locate the tweet.js file, it should be under unzippedFolder/data/tweet.js

4. Copy tweet.js and rename it to whatever you want, but make sure you open the copy and delete the  `window.YTD.tweet.part0 = ` substring.

5. Define your configuration file for the script and save it with a `.properties` extension. It should look something like:

```
consumerKey=xxx
consumerSecret=xxx
accessToken=xxx
accessTokenSecret=xxx
tweetsToDeletePath=pathToUnzippedFolder/data/editedTweet.js
deletedTweetsPath=pathToWhereYouWantTheDeletionLogToBe/whateverYouWantTheLogFileToBeCalled.csv
favoritesThreshold=2
retweetsThreshold=2
cutOffDate=2015/01/01
```

The `consumerKey`, `consumerSecret`, `accessToken`, and `accessTokenSecret` will all need to be gotten from your Twitter developer app dashboard.

Some of your old tweets are gems, and what better way to determine this than likes or retweets stats?
`favoritesThreshold` and `retweetsThreshold` allow you to keep your classics from being deleted; tweets at or above either threshold will be kept.

`cutOffDate` lets you specify when to stop deleting tweets; all tweets in the archive before the specified date will be processed.

6. Run the script: `kotlin TweetusDeletus.main.kts pathToConfig.properties`.

Example: `kotlin TweetusDeletus.main.kts /Users/adetunjidahunsi/Desktop/tweetusDeletus.properties`
