package io.github.pulpogato.rest.schemas;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.pulpogato.test.TestUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPingTest {
    String input =
    // language=JSON
            """
            {
              "zen": "Speak like a human.",
              "hook_id": 505365154,
              "hook": {
                "type": "Repository",
                "id": 505365154,
                "name": "web",
                "active": true,
                "events": [
                  "push"
                ],
                "config": {
                  "content_type": "json",
                  "insecure_ssl": "0",
                  "url": "https://411e-2405-201-e016-382f-258e-20d2-ec59-b1b0.ngrok-free.app/github-webhook"
                },
                "updated_at": "2024-10-04T03:08:48Z",
                "created_at": "2024-10-04T03:08:48Z",
                "url": "https://api.github.com/repos/rahulsom/nothing/hooks/505365154",
                "test_url": "https://api.github.com/repos/rahulsom/nothing/hooks/505365154/test",
                "ping_url": "https://api.github.com/repos/rahulsom/nothing/hooks/505365154/pings",
                "deliveries_url": "https://api.github.com/repos/rahulsom/nothing/hooks/505365154/deliveries",
                "last_response": {
                  "code": null,
                  "status": "unused",
                  "message": null
                }
              },
              "repository": {
                "id": 340734866,
                "node_id": "MDEwOlJlcG9zaXRvcnkzNDA3MzQ4NjY=",
                "name": "nothing",
                "full_name": "rahulsom/nothing",
                "private": false,
                "owner": {
                  "login": "rahulsom",
                  "id": 193047,
                  "node_id": "MDQ6VXNlcjE5MzA0Nw==",
                  "avatar_url": "https://avatars.githubusercontent.com/u/193047?v=4",
                  "gravatar_id": "",
                  "url": "https://api.github.com/users/rahulsom",
                  "html_url": "https://github.com/rahulsom",
                  "followers_url": "https://api.github.com/users/rahulsom/followers",
                  "following_url": "https://api.github.com/users/rahulsom/following{/other_user}",
                  "gists_url": "https://api.github.com/users/rahulsom/gists{/gist_id}",
                  "starred_url": "https://api.github.com/users/rahulsom/starred{/owner}{/repo}",
                  "subscriptions_url": "https://api.github.com/users/rahulsom/subscriptions",
                  "organizations_url": "https://api.github.com/users/rahulsom/orgs",
                  "repos_url": "https://api.github.com/users/rahulsom/repos",
                  "events_url": "https://api.github.com/users/rahulsom/events{/privacy}",
                  "received_events_url": "https://api.github.com/users/rahulsom/received_events",
                  "type": "User",
                  "site_admin": false
                },
                "html_url": "https://github.com/rahulsom/nothing",
                "description": null,
                "fork": false,
                "url": "https://api.github.com/repos/rahulsom/nothing",
                "forks_url": "https://api.github.com/repos/rahulsom/nothing/forks",
                "keys_url": "https://api.github.com/repos/rahulsom/nothing/keys{/key_id}",
                "collaborators_url": "https://api.github.com/repos/rahulsom/nothing/collaborators{/collaborator}",
                "teams_url": "https://api.github.com/repos/rahulsom/nothing/teams",
                "hooks_url": "https://api.github.com/repos/rahulsom/nothing/hooks",
                "issue_events_url": "https://api.github.com/repos/rahulsom/nothing/issues/events{/number}",
                "events_url": "https://api.github.com/repos/rahulsom/nothing/events",
                "assignees_url": "https://api.github.com/repos/rahulsom/nothing/assignees{/user}",
                "branches_url": "https://api.github.com/repos/rahulsom/nothing/branches{/branch}",
                "tags_url": "https://api.github.com/repos/rahulsom/nothing/tags",
                "blobs_url": "https://api.github.com/repos/rahulsom/nothing/git/blobs{/sha}",
                "git_tags_url": "https://api.github.com/repos/rahulsom/nothing/git/tags{/sha}",
                "git_refs_url": "https://api.github.com/repos/rahulsom/nothing/git/refs{/sha}",
                "trees_url": "https://api.github.com/repos/rahulsom/nothing/git/trees{/sha}",
                "statuses_url": "https://api.github.com/repos/rahulsom/nothing/statuses/{sha}",
                "languages_url": "https://api.github.com/repos/rahulsom/nothing/languages",
                "stargazers_url": "https://api.github.com/repos/rahulsom/nothing/stargazers",
                "contributors_url": "https://api.github.com/repos/rahulsom/nothing/contributors",
                "subscribers_url": "https://api.github.com/repos/rahulsom/nothing/subscribers",
                "subscription_url": "https://api.github.com/repos/rahulsom/nothing/subscription",
                "commits_url": "https://api.github.com/repos/rahulsom/nothing/commits{/sha}",
                "git_commits_url": "https://api.github.com/repos/rahulsom/nothing/git/commits{/sha}",
                "comments_url": "https://api.github.com/repos/rahulsom/nothing/comments{/number}",
                "issue_comment_url": "https://api.github.com/repos/rahulsom/nothing/issues/comments{/number}",
                "contents_url": "https://api.github.com/repos/rahulsom/nothing/contents/{+path}",
                "compare_url": "https://api.github.com/repos/rahulsom/nothing/compare/{base}...{head}",
                "merges_url": "https://api.github.com/repos/rahulsom/nothing/merges",
                "archive_url": "https://api.github.com/repos/rahulsom/nothing/{archive_format}{/ref}",
                "downloads_url": "https://api.github.com/repos/rahulsom/nothing/downloads",
                "issues_url": "https://api.github.com/repos/rahulsom/nothing/issues{/number}",
                "pulls_url": "https://api.github.com/repos/rahulsom/nothing/pulls{/number}",
                "milestones_url": "https://api.github.com/repos/rahulsom/nothing/milestones{/number}",
                "notifications_url": "https://api.github.com/repos/rahulsom/nothing/notifications{?since,all,participating}",
                "labels_url": "https://api.github.com/repos/rahulsom/nothing/labels{/name}",
                "releases_url": "https://api.github.com/repos/rahulsom/nothing/releases{/id}",
                "deployments_url": "https://api.github.com/repos/rahulsom/nothing/deployments",
                "created_at": "2021-02-20T19:21:06Z",
                "updated_at": "2024-10-02T04:30:28Z",
                "pushed_at": "2024-10-02T04:30:25Z",
                "git_url": "git://github.com/rahulsom/nothing.git",
                "ssh_url": "git@github.com:rahulsom/nothing.git",
                "clone_url": "https://github.com/rahulsom/nothing.git",
                "svn_url": "https://github.com/rahulsom/nothing",
                "homepage": "",
                "size": 316,
                "stargazers_count": 0,
                "watchers_count": 0,
                "language": "Java",
                "has_issues": true,
                "has_projects": true,
                "has_downloads": true,
                "has_wiki": true,
                "has_pages": false,
                "has_discussions": false,
                "forks_count": 0,
                "mirror_url": null,
                "archived": false,
                "disabled": false,
                "open_issues_count": 1,
                "license": null,
                "allow_forking": true,
                "is_template": false,
                "web_commit_signoff_required": false,
                "topics": [],
                "visibility": "public",
                "forks": 0,
                "open_issues": 1,
                "watchers": 0,
                "default_branch": "master"
              },
              "sender": {
                "login": "rahulsom",
                "id": 193047,
                "node_id": "MDQ6VXNlcjE5MzA0Nw==",
                "avatar_url": "https://avatars.githubusercontent.com/u/193047?v=4",
                "gravatar_id": "",
                "url": "https://api.github.com/users/rahulsom",
                "html_url": "https://github.com/rahulsom",
                "followers_url": "https://api.github.com/users/rahulsom/followers",
                "following_url": "https://api.github.com/users/rahulsom/following{/other_user}",
                "gists_url": "https://api.github.com/users/rahulsom/gists{/gist_id}",
                "starred_url": "https://api.github.com/users/rahulsom/starred{/owner}{/repo}",
                "subscriptions_url": "https://api.github.com/users/rahulsom/subscriptions",
                "organizations_url": "https://api.github.com/users/rahulsom/orgs",
                "repos_url": "https://api.github.com/users/rahulsom/repos",
                "events_url": "https://api.github.com/users/rahulsom/events{/privacy}",
                "received_events_url": "https://api.github.com/users/rahulsom/received_events",
                "type": "User",
                "site_admin": false
              }
            }
            """;

    @Test
    void shouldParse() throws JsonProcessingException {
        WebhookPing webhookPing = TestUtils.parseAndCompare(WebhookPing.class, input);

        assertThat(webhookPing.getHook().getConfig().getInsecureSsl().getWebhookConfigInsecureSsl0()).isEqualTo("0");
    }

}
