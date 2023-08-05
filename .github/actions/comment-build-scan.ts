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
  const octokit = new Octokit();

  const prNumber = parseInt(process.env.PR_NUMBER);
  const scans = process.env.BUILD_SCANS.split("\n");
  const owner = 'ikhoon';
  const repo = 'armeria';

  const comments = await octokit.rest.issues.listComments({
    owner,
    repo,
    issue_number: prNumber,
  })

  let commentBody = `#### 🔍 Gradle build scans (commit: ${process.env.SHA})\n\n`;
  for (const scan of scans) {
    // scan string pattern: "build_scan_<job-id> https://ge.armeria.dev/xxxxxx"
    const tokens = scan.split(" ");
    const jobId = tokens[0].replace("build_scan_", "")
    const scanUrl = tokens[1];
    const job = await octokit.rest.actions.getJobForWorkflowRun({
      owner: owner,
      repo: repo,
      job_id: parseInt(jobId)
    });
    if (job.data.conclusion === 'success') {
      commentBody += `✅ [${job.data.name}](${job.data.url}) - ${scanUrl}\n`;
    } else {
      commentBody += `❌ [${job.data.name}](${job.data.url}) (${job.data.conclusion}) - ${scanUrl}\n`;
    }
  }

  const scanComment = comments.data.find(comment =>
    comment.user.login === "github-actions[bot]" && comment.body.includes('Gradle build scans'))
  if (scanComment) {
    // Update the previous comment
    await octokit.rest.issues.updateComment({
      owner,
      repo,
      comment_id: scanComment.id,
      body: commentBody
    })
  } else {
    // If no previous comment, create a new one
    await octokit.rest.issues.createComment({
      owner,
      repo,
      issue_number: prNumber,
      body: commentBody
    })
  }
}
