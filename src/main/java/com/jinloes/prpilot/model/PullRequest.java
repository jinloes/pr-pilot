package com.jinloes.prpilot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString(of = {"owner", "repo", "number", "title"})
public class PullRequest {
    private final String title;
    private final String htmlUrl;
    private final String owner;
    private final String repo;
    private final int number;
    private final String body;
    private final String author;
    private final String createdAt;
}
