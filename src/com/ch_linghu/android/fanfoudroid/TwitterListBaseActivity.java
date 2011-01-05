/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * AbstractTwitterListBaseLine用于抽象tweets List的展现
 * UI基本元素要求：一个ListView用于tweet列表
 *               一个ProgressText用于提示信息
 */
package com.ch_linghu.android.fanfoudroid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.ch_linghu.android.fanfoudroid.TwitterApi.ApiException;
import com.ch_linghu.android.fanfoudroid.TwitterApi.AuthException;
import com.google.android.photostream.UserTask;

public abstract class TwitterListBaseActivity extends WithHeaderActivity
		implements Refreshable {
	static final String TAG = "TwitterListBaseActivity";

	protected TextView mProgressText;

	protected static final int STATE_ALL = 0;

	// Tasks.
	protected UserTask<String, Void, SendResult> mFavTask;

	static final int DIALOG_WRITE_ID = 0;
	
	abstract protected ListView getTweetList();
	abstract protected TweetAdapter getTweetAdapter();
	abstract protected void setupState();

	abstract protected String getActivityTitle();
	abstract protected boolean useBasicMenu();
	
	abstract protected Tweet getContextItemTweet(int position);

	abstract protected void updateTweet(Tweet tweet);



	public static final int CONTEXT_REPLY_ID = Menu.FIRST + 1;
	// public static final int CONTEXT_AT_ID = Menu.FIRST + 2;
	public static final int CONTEXT_RETWEET_ID = Menu.FIRST + 3;
	public static final int CONTEXT_DM_ID = Menu.FIRST + 4;
	public static final int CONTEXT_MORE_ID = Menu.FIRST + 5;
	public static final int CONTEXT_ADD_FAV_ID = Menu.FIRST + 6;
	public static final int CONTEXT_DEL_FAV_ID = Menu.FIRST + 7;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (!getApi().isLoggedIn()) {
			Log.i(TAG, "Not logged in.");
			handleLoggedOut();
			return;
		}

		setContentView(R.layout.main);
		initHeader(HEADER_STYLE_HOME, this);

		mPreferences.getInt(Preferences.TWITTER_ACTIVITY_STATE_KEY, STATE_ALL);

		// 提示栏
		mProgressText = (TextView) findViewById(R.id.progress_text);
		
		setupState();

		registerForContextMenu(getTweetList());
	}

	@Override
	protected void onResume() {
		super.onResume();

		if (!getApi().isLoggedIn()) {
			Log.i(TAG, "Not logged in.");
			handleLoggedOut();
			return;
		}
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);

		if (useBasicMenu()){
			AdapterView.AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
			Tweet tweet = getContextItemTweet(info.position);
			
			menu.add(0, CONTEXT_MORE_ID, 0, tweet.screenName + " 的空间");
			menu.add(0, CONTEXT_REPLY_ID, 0, R.string.reply);
			menu.add(0, CONTEXT_RETWEET_ID, 0, R.string.retweet);
			menu.add(0, CONTEXT_DM_ID, 0, R.string.dm);
	
			if (tweet.favorited.equals("true")) {
				menu.add(0, CONTEXT_DEL_FAV_ID, 0, R.string.del_fav);
			} else {
				menu.add(0, CONTEXT_ADD_FAV_ID, 0, R.string.add_fav);
			}
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo();
		Tweet tweet = getContextItemTweet(info.position);

		if (tweet == null) {
			Log.w(TAG, "Selected item not available.");
			return super.onContextItemSelected(item);
		}

		switch (item.getItemId()) {
		case CONTEXT_MORE_ID:
			launchActivity(UserActivity.createIntent(tweet.userId));
			return true;
		case CONTEXT_REPLY_ID: {
			// TODO: this isn't quite perfect. It leaves extra empty spaces if
			// you
			// perform the reply action again.
			String replyTo = "@" + tweet.screenName + " ";
			Intent intent = new Intent(WriteActivity.NEW_TWEET_ACTION, null,
					this, WriteActivity.class);
			intent.putExtra(WriteActivity.EXTRA_TEXT, replyTo);
			intent.putExtra(WriteActivity.REPLY_ID, tweet.id);
			startActivity(intent);

			return true;
		}
		case CONTEXT_RETWEET_ID:
			String prefix = mPreferences.getString(Preferences.RT_PREFIX_KEY,
					getString(R.string.pref_rt_prefix_default));
			String retweet = " "
					+ prefix
					+ " @"
					+ tweet.screenName
					+ " "
					+ tweet.text.replaceAll("<.*?>", "");//TODO: 使用更好的方法对TEXT进行格式化
			Intent intent = new Intent(WriteActivity.NEW_TWEET_ACTION, null,
					this, WriteActivity.class);
			intent.putExtra(WriteActivity.EXTRA_TEXT, retweet);
			intent.putExtra(WriteActivity.REPLY_ID, tweet.id);
			startActivity(intent);

			return true;
		case CONTEXT_DM_ID:
			launchActivity(DmActivity.createIntent(tweet.userId));
			return true;
		case CONTEXT_ADD_FAV_ID: 
			doFavorite("add", tweet.id);
			return true;
		case CONTEXT_DEL_FAV_ID: 
			doFavorite("del", tweet.id);
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case OPTIONS_MENU_ID_REFRESH:
			doRetrieve();
			return true;
		case OPTIONS_MENU_ID_TWEETS:
			launchActivity(TwitterActivity.createIntent(this));
			return true;
	      case OPTIONS_MENU_ID_REPLIES:
	        launchActivity(MentionActivity.createIntent(this));
	        return true;
	      case OPTIONS_MENU_ID_DM:
	        launchActivity(DmActivity.createIntent());
	        return true;
	    }

		return super.onOptionsItemSelected(item);
	}

	private void draw() {
		getTweetAdapter().refresh();
	}

	private void goTop() {
		getTweetList().setSelection(0);
	}

	private void doFavorite(String action, String id) {
		if (mFavTask != null && mFavTask.getStatus() == UserTask.Status.RUNNING) {
			Log.w(TAG, "FavTask still running");
		} else {
			if (!Utils.isEmpty(id)) {
				mFavTask = new FavTask().execute(action, id);
			}
		}
	}

	private enum SendResult {
		OK, IO_ERROR, AUTH_ERROR, CANCELLED
	}

	private class FavTask extends UserTask<String, Void, SendResult> {
		@Override
		public void onPreExecute() {
			// onSendBegin();
		}

		@Override
		public SendResult doInBackground(String... params) {
			try {
				String action = params[0];
				String id = params[1];
				JSONObject jsonObject = null;
				if (action.equals("add")) {
					jsonObject = getApi().addFavorite(id);
				} else {
					jsonObject = getApi().delFavorite(id);
				}

				Tweet tweet = Tweet.create(jsonObject);

				if (!Utils.isEmpty(tweet.profileImageUrl)) {
					// Fetch image to cache.
					try {
						getImageManager().put(tweet.profileImageUrl);
					} catch (IOException e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}

				updateTweet(tweet);
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
				return SendResult.IO_ERROR;
			} catch (AuthException e) {
				Log.i(TAG, "Invalid authorization.");
				return SendResult.AUTH_ERROR;
			} catch (JSONException e) {
				Log.w(TAG, "Could not parse JSON after sending update.");
				return SendResult.IO_ERROR;
			} catch (ApiException e) {
				Log.e(TAG, e.getMessage(), e);
				return SendResult.IO_ERROR;
			}

			return SendResult.OK;
		}

		@Override
		public void onPostExecute(SendResult result) {
			if (isCancelled()) {
				// Canceled doesn't really mean "canceled" in this task.
				// We want the request to complete, but don't want to update the
				// activity (it's probably dead).
				return;
			}

			if (result == SendResult.AUTH_ERROR) {
				logout();
			} else if (result == SendResult.OK) {
				onSendSuccess();
			} else if (result == SendResult.IO_ERROR) {
				onSendFailure();
			}
		}

		private void onSendSuccess() {
			// updateProgress(getString(R.string.refreshing));
			adapterRefresh();
		}

		private void onSendFailure() {
			// updateProgress(getString(R.string.refreshing));
		}
	}

	protected void adapterRefresh(){
		getTweetAdapter().refresh();
	}
}