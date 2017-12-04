package main.java;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.ZParams;

import java.util.*;

public class Chapter01 {
    private static final int ONE_WEEK_IN_SECONDS = 7 * 86400;
    private static final int VOTE_SCORE = 432;
    private static final int ARTICLES_PER_PAGE = 25;

    public static final void main(String[] args) {
        new Chapter01().run();
    }

    public void run() {
    	//建立redis连接     
    	Jedis conn = new Jedis("localhost",6379);
        conn.auth("test123");//密码验证
        conn.select(15);
        String articleId = postArticle(
            conn, "username", "A title", "http://www.google.com");
        System.out.println("We posted a new article with id: " + articleId);
        System.out.println("Its HASH looks like:");
        //h获取hash散列里的数据
        Map<String,String> articleData = conn.hgetAll("article:" + articleId);
        for (Map.Entry<String,String> entry : articleData.entrySet()){
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        System.out.println();
//        String articleId ="7";
        articleVote(conn, "other_user", "article:" + articleId);
        String votes = conn.hget("article:" + articleId, "votes");
        System.out.println("We voted for the article, it now has votes: " + votes);
        //如果<boolean表达式>为true，则程序继续执行。如果为false，则程序抛出AssertionError，并终止执行。
        assert Integer.parseInt(votes) > 1;

        System.out.println("The currently highest-scoring articles are:");
        List<Map<String,String>> articles = getArticles(conn, 1);
        printArticles(articles);
        assert articles.size() >= 1;

        addGroups(conn, articleId, new String[]{"new-group"});
        System.out.println("We added the article to a new group, other articles include:");
        articles = getGroupArticles(conn, "new-group", 1);
        printArticles(articles);
        assert articles.size() >= 1;
    }
    
    //生成文章信息函数和文章set集合容器
    public String postArticle(Jedis conn, String user, String title, String link) {
    	//获取文章编号
        String articleId = String.valueOf(conn.incr("article:"));
        String voted = "voted:" + articleId;
        System.out.println(voted);
        
        //给voted编号的set添加投票一名投票名单
        conn.sadd(voted, user);
        //set之voted编号的失效时间
        conn.expire(voted, ONE_WEEK_IN_SECONDS);

        long now = System.currentTimeMillis() / 1000;
        String article = "article:" + articleId;
        HashMap<String,String> articleData = new HashMap<String,String>();
        articleData.put("title", title);
        articleData.put("link", link);
        articleData.put("user", user);
        articleData.put("now", String.valueOf(now));
        articleData.put("votes", "1");
        //hash散列
        conn.hmset(article, articleData);
        //给article这篇文章初始化的时候定义的分数
        conn.zadd("score:", now + VOTE_SCORE, article);
        //给article这篇文章初始化的时候定义的时间
        conn.zadd("time:", now, article);
        return articleId;
    }
    //文章投票函数article  article:1
    public void articleVote(Jedis conn, String user, String article) {
        long cutoff = (System.currentTimeMillis() / 1000) - ONE_WEEK_IN_SECONDS;
        //已过投票日期
        if (conn.zscore("time:", article) < cutoff){
            return;
        }

        String articleId = article.substring(article.indexOf(':') + 1);
        //此用户投票成功的操作
        if (conn.sadd("voted:" + articleId, user) == 1) {
            conn.zincrby("score:", VOTE_SCORE, article);
            //hash 自增1l
            conn.hincrBy(article, "votes", 1l);
        }
    }


    public List<Map<String,String>> getArticles(Jedis conn, int page) {
        return getArticles(conn, page, "score:");
    }
    //获取文章对象
    public List<Map<String,String>> getArticles(Jedis conn, int page, String order) {
        int start = (page - 1) * ARTICLES_PER_PAGE;
        int end = start + ARTICLES_PER_PAGE - 1;
        //获取截取的key数据
        Set<String> ids = conn.zrevrange(order, start, end);
        List<Map<String,String>> articles = new ArrayList<Map<String,String>>();
        for (String id : ids){
            Map<String,String> articleData = conn.hgetAll(id);
            articleData.put("id", id);
            articles.add(articleData);
        }
        return articles;
    }

    public void addGroups(Jedis conn, String articleId, String[] toAdd) {
        String article = "article:" + articleId;
        for (String group : toAdd) {
            conn.sadd("group:" + group, article);
        }
    }

    public List<Map<String,String>> getGroupArticles(Jedis conn, String group, int page) {
        return getGroupArticles(conn, group, page, "score:");
    }

    public List<Map<String,String>> getGroupArticles(Jedis conn, String group, int page, String order) {
        String key = order + group;
        if (!conn.exists(key)) {
            ZParams params = new ZParams().aggregate(ZParams.Aggregate.MAX);
            conn.zinterstore(key, params, "group:" + group, order);
            conn.expire(key, 60);
        }
        return getArticles(conn, page, key);
    }

    private void printArticles(List<Map<String,String>> articles){
        for (Map<String,String> article : articles){
            System.out.println("  id: " + article.get("id"));
            for (Map.Entry<String,String> entry : article.entrySet()){
                if (entry.getKey().equals("id")){
                    continue;
                }
                System.out.println("    " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }
}
