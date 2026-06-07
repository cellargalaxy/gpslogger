/*
 * Travel/hiking 改造：Custom URL Outbox 队列管理界面。
 * 展示最新若干条 Outbox 行的状态、错误，并提供「重试所有失败」「清空」操作。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.tracker.db.OutboxEntry;
import com.mendhak.gpslogger.tracker.outbox.OutboxRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OutboxQueueActivity extends AppCompatActivity {

    private TextView summary;
    private ListView list;
    private final ArrayList<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbox_queue);
        setTitle(R.string.tracker_outbox_queue_activity_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        summary = findViewById(R.id.outbox_summary);
        list = findViewById(R.id.outbox_list);
        Button retry = findViewById(R.id.outbox_retry_failed);
        Button clear = findViewById(R.id.outbox_clear_all);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);

        retry.setOnClickListener(v -> {
            int n = OutboxRepository.getInstance().requeueAllFailed();
            Toast.makeText(this, getString(R.string.tracker_outbox_requeued_format, n), Toast.LENGTH_SHORT).show();
            refresh();
        });

        clear.setOnClickListener(v -> {
            int n = OutboxRepository.getInstance().clearAll();
            Toast.makeText(this, getString(R.string.tracker_outbox_cleared_format, n), Toast.LENGTH_SHORT).show();
            refresh();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        OutboxRepository repo = OutboxRepository.getInstance();
        int total = repo.countTotal();
        int pending = repo.countPending();
        int failed = repo.countFailed();
        summary.setText(getString(R.string.tracker_outbox_summary_format, total, pending, failed));

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        List<OutboxEntry> sample = repo.sampleLatest(200);
        rows.clear();
        for (OutboxEntry e : sample) {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(e.status).append("] ");
            sb.append(fmt.format(new Date(e.recordedAt)));
            sb.append("  ").append(e.method).append(' ').append(truncate(e.url, 60));
            if (e.attemptCount > 0) sb.append("\n  attempts=").append(e.attemptCount);
            if (e.lastError != null) sb.append("\n  ").append(truncate(e.lastError, 120));
            rows.add(sb.toString());
        }
        adapter.notifyDataSetChanged();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
