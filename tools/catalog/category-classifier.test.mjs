import assert from 'node:assert/strict';
import { classifyGeneratedFeedCategory } from './category-classifier.mjs';

assert.equal(classifyGeneratedFeedCategory('架构师之路', 'https://plink.anyfeeder.com/weixin/gh_10a6b96351a9'), 'dev');
assert.equal(classifyGeneratedFeedCategory('丁香医生', 'https://plink.anyfeeder.com/weixin/DingXiangYiSheng'), 'science');
assert.equal(classifyGeneratedFeedCategory('果壳网', 'https://plink.anyfeeder.com/weixin/Guokr42'), 'science');
assert.equal(classifyGeneratedFeedCategory('Readhub / 开发者资讯', 'https://plink.anyfeeder.com/readhub/technews'), 'dev');
assert.equal(classifyGeneratedFeedCategory('匿名来源', 'https://plink.anyfeeder.com/weixin/unknown'), 'other');
