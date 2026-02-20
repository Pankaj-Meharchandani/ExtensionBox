package com.extensionbox.app.network;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface GitHubService {
    @GET("repos/{owner}/{repo}/releases")
    Call<List<Release>> getReleases(@Path("owner") String owner, @Path("repo") String repo);
}
