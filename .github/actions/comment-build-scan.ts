/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { Octokit } from 'octokit';

main();

async function main(): Promise<void> {
  const octokit = new Octokit({auth: process.env.GITHUB_TOKEN});
  const scans = process.env.BUILD_SCANS.split(",");
  // TODO(ikhoon): Convert to line/armeria
  const owner = 'ikhoon';
  const repo = 'armeria';

  console.log(`💻 Getting pull request number for ${process.env.SHA}...`)
  const {data: {check_suites}} = await octokit.rest.checks.listSuitesForRef({
    owner: owner,
    repo: repo,
    ref: process.env.SHA,
  });

  let prNumber = 0;
  for (const item of check_suites) {
    if (item.pull_requests.length > 0) {
      prNumber = item.pull_requests[0].number;
      break;
    }
  }
  if (prNumber === 0) {
    // The build is not triggered by a pull request.
    console.log("⌀ No pull request found. Skip creating a scan comment.");
    return;
  }

  console.log(`💻 Getting jobs for ${process.env.RUN_ID} ...`)
  const {data: {jobs}} = await octokit.rest.actions.listJobsForWorkflowRun({
    owner: owner,
    repo: repo,
    run_id: parseInt(process.env.RUN_ID),
  });

  console.log(`💻 Getting comments for #${prNumber} ...`)
  const comments = await octokit.rest.issues.listComments({
    owner,
    repo,
    issue_number: prNumber,
  })

  let commentBody = `## 🔍 Build Scan® (commit: ${process.env.SHA})\n\n`;
  for (const scan of scans) {
    if (scan.trim().length === 0) {
      continue;
    }
    // scan string pattern: "build-scan-<job-name> https://ge.armeria.dev/xxxxxx"
    const tokens = scan.split(" ");
    const jobName = tokens[0].replace("build-scan-", "")
    const job = jobs.find(job => job.name === jobName);
    const scanUrl = tokens[1].trim();
    if (job.conclusion === 'success') {
      commentBody += `✅ [${job.name}](${job.url}) - ${scanUrl}\n`;
    } else {
      commentBody += `❌ [${job.name}](${job.url}) (${job.conclusion}) - ${scanUrl}\n`;
    }
  }

  const scanComment = comments.data.find(comment =>
    comment.user.login === "github-actions[bot]" && comment.body.includes('Build scans®'))
  if (scanComment) {
    // Update the previous comment
    console.log(`📝 Updating the previous comment: ${scanComment.html_url} ...`)
    await octokit.rest.issues.updateComment({
      owner,
      repo,
      comment_id: scanComment.id,
      body: commentBody
    })
  } else {
    // If no previous comment, create a new one
    console.log(`📝 Creating a new comment for #${prNumber} ...`)
    const { data: newComment } = await octokit.rest.issues.createComment({
      owner,
      repo,
      issue_number: prNumber,
      body: commentBody
    })
    console.log(`💬 A new comment has been created: ${newComment.html_url}`)
  }
}
