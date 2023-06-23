import {Octokit} from '@octokit/action';
import * as Actions from '@actions/core';

const octokit = new Octokit();

main();

async function main(): Promise<void> {
  const tag = Actions.getInput("tag");
  const releaseVersion = tag.replace("armeria-", "");
  const owner = 'ikhoon';
  const repo = 'armeria';
  const milestoneId = await getMilestoneId(owner, repo, releaseVersion);

  // Close the milestone
  Actions.info(`[info] 🎯 Closing milestone #${milestoneId}...`);
  await octokit.rest.issues.updateMilestone({
    owner,
    repo,
    milestone_number: milestoneId,
    due_on: new Date().toISOString(),
    state: "closed"
  })
  Actions.info(`https://github.com/line/armeria/milestone/${milestoneId} has been closed.`)

  // Update release notes
  const releaseInfo = await octokit.rest.repos.getReleaseByTag({owner, repo, tag});
  const releaseId = releaseInfo.data.id;
  Actions.info(`[info] 📝 Updating release notes #${releaseId}...`);
  await octokit.rest.repos.updateRelease({
    owner,
    repo,
    release_id: releaseId,
    body: `See [the release notes](https://armeria.dev/release-notes/${releaseVersion}/) for the complete change list.`
  });
  Actions.info(`https://github.com/line/armeria/releases/tag/${tag} has been updated.`)

  // Trigger Central Dogma workflow to upgrade Armeria version
  const cdOctokit = new Octokit({ auth: process.env.GITHUB_CD_ACCESS_TOKEN });
  Actions.info(`[info] ⛓️ Triggering 'update-armeria-version' workflow in Central Dogma repository...`);
  await cdOctokit.rest.repos.createDispatchEvent({
    owner: owner,
    repo: 'centraldogma',
    event_type: 'update-armeria-version',
    client_payload: {
      armeria_version: releaseVersion
    },
  })
  Actions.info("https://github.com/line/centraldogma/actions/workflows/update-armeria-version.yml has been triggered")
}

// Create a pull request to Central Dogma for updating Armeria version if the current version is a minor patch.

/**
 * Converts a version into a milestone number in GitHub.
 */
async function getMilestoneId(owner: string, repo: string, version: string): Promise<number> {
  const response = await octokit.request(
      'GET /repos/{owner}/{repo}/milestones',
      {
        owner: 'line',
        repo: 'armeria',
        direction: 'desc',
        per_page: 100,
        state: 'open',
      },
  );
  const found = response.data.find((milestone: any) => milestone.title === version);
  if (!found) {
    throw new Error(
        `Failed to find a milestone from the given version: ${version}.`,
    );
  }
  return found.number;
}

