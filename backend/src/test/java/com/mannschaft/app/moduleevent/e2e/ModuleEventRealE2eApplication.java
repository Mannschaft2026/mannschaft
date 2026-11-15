package com.mannschaft.app.moduleevent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.MannschaftApplication;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * L1-M 実機E2E用の BE 起動入口（2026-11-15 マスター裁可 S4）。
 *
 * <p>本番には ReliableEvent の採用がないため、通常の {@link MannschaftApplication} に
 * 試験用の検体 {@link ModuleEventRealE2eFixtures} を明示登録して起動する。
 * 起動は {@code ./gradlew bootRunModuleEventE2e}（既定 8081・local プロファイル・通常の MySQL/Valkey）。
 *
 * <p>実行ディレクトリ（既定 {@code backend/build/l1m-real-e2e}、環境変数 {@code L1M_E2E_RUN_DIR} で上書き）へ
 * 検体 actor の資格情報 {@code actors.json} と検体状態 {@code state.json} を書き出し、
 * {@code controls/} 配下に spec が作るファイルを制御コマンドとして受け取る。
 * 終了（Ctrl+C か {@code controls/shutdown}）時に、検体が生成した ID のデータだけを撤去する。
 * 製品 resolver や C/F 実業務の接続証明には使わない。
 */
public final class ModuleEventRealE2eApplication {
    static final String RUN_DIR_ENV = "L1M_E2E_RUN_DIR";
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);
    private static final Set<String> COMMANDS = Set.of("release-delayed", "revoke-and-seed-quarantine", "release-last");

    private ModuleEventRealE2eApplication() { }

    public static void main(String[] args) throws Exception {
        Path run = Path.of(System.getenv().getOrDefault(RUN_DIR_ENV, "build/l1m-real-e2e")).toAbsolutePath().normalize();
        Path controls = run.resolve("controls");
        prepareRunDirectory(run, controls);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        SpringApplication application = new SpringApplication(MannschaftApplication.class, ModuleEventRealE2eFixtures.class);
        application.addInitializers(context -> validate(context.getEnvironment()));
        // 検体の撤去を context close より先に行うため、Spring 既定の shutdown hook は使わず自前で順序を持つ。
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(args);
        ModuleEventRealE2eFixtures.Domain domain = context.getBean(ModuleEventRealE2eFixtures.Domain.class);
        ModuleEventRealE2eFixtures.World world = context.getBean(ModuleEventRealE2eFixtures.World.class);
        Thread hook = new Thread(() -> {
            STOPPING.set(true);
            shutdown(context, domain, world, run);
        }, "l1m-real-e2e-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        Set<String> accepted = new HashSet<>();
        try {
            domain.seed();
            write(json, run.resolve("actors.json"), world.credentials());
            write(json, run.resolve("state.json"), domain.snapshot());
            System.out.println("[L1M-E2E] 検体を投入した。run=" + run);
            while (!STOPPING.get() && !Files.exists(controls.resolve("shutdown"))) {
                try (Stream<Path> files = Files.list(controls)) {
                    for (Path file : files.sorted().toList()) {
                        String command = file.getFileName().toString();
                        if ("shutdown".equals(command)) continue;
                        ModuleEventRealE2eFixtures.require(COMMANDS.contains(command), "未知の制御command: " + command);
                        ModuleEventRealE2eFixtures.require(accepted.add(command), "制御commandの重複: " + command);
                        switch (command) {
                            case "release-delayed" -> { world.release("delayed"); world.release("organization"); }
                            case "revoke-and-seed-quarantine" -> domain.revokeAndSeedQuarantine();
                            case "release-last" -> world.release("quarantine");
                            default -> throw new IllegalStateException("未知の制御command: " + command);
                        }
                        Files.delete(file);
                        System.out.println("[L1M-E2E] 制御command を処理した: " + command);
                    }
                }
                write(json, run.resolve("state.json"), domain.snapshot());
                TimeUnit.MILLISECONDS.sleep(500);
            }
        } catch (InterruptedException | RuntimeException | IOException failure) {
            // shutdown hook 経由の終了中なら撤去は hook 側が行う。
            if (STOPPING.get()) return;
            // 投入・制御の失敗は隠さず表示し、検体を撤去してから異常終了する。
            failure.printStackTrace();
            Runtime.getRuntime().removeShutdownHook(hook);
            shutdown(context, domain, world, run);
            System.exit(1);
        }
        if (STOPPING.get()) return;
        Runtime.getRuntime().removeShutdownHook(hook);
        shutdown(context, domain, world, run);
        System.exit(0);
    }

    /** 実行ディレクトリは本入口が所有する固定場所。前回の自己出力だけを消してから使う。 */
    private static void prepareRunDirectory(Path run, Path controls) throws IOException {
        Files.createDirectories(controls);
        try (Stream<Path> files = Files.list(controls)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        for (String name : List.of("actors.json", "state.json", "final-state.json")) {
            Files.deleteIfExists(run.resolve(name));
        }
    }

    /** 検体 gate 解放 → worker 停止 → 生成 ID 撤去 → context close。失敗は握りつぶさず標準エラーへ出す。 */
    private static synchronized void shutdown(ConfigurableApplicationContext context,
            ModuleEventRealE2eFixtures.Domain domain, ModuleEventRealE2eFixtures.World world, Path run) {
        if (!context.isActive()) return;
        List<Throwable> failures = new ArrayList<>();
        attempt(failures, world::releaseAll);
        attempt(failures, () -> stopWorkers(context));
        attempt(failures, () -> write(new ObjectMapper().findAndRegisterModules(), run.resolve("final-state.json"), domain.snapshot()));
        attempt(failures, () -> { if (!world.users.isEmpty()) domain.cleanup(); });
        attempt(failures, () -> Files.deleteIfExists(run.resolve("actors.json")));
        attempt(failures, context::close);
        if (failures.isEmpty()) {
            System.out.println("[L1M-E2E] 検体データを撤去して停止した。");
            return;
        }
        IllegalStateException problem = new IllegalStateException("L1-M 実機E2E 検体の撤去に失敗した。生成ID: " + world.users);
        failures.forEach(problem::addSuppressed);
        problem.printStackTrace();
    }

    private interface Step { void run() throws Exception; }

    private static void attempt(List<Throwable> failures, Step step) {
        try {
            step.run();
        } catch (Exception | Error failure) {
            failures.add(failure);
        }
    }

    /** 本番・共有環境への誤投入を防ぐ。local 系の構成とループバックの MySQL だけを受け付ける。 */
    private static void validate(Environment environment) {
        List<String> profiles = List.of(environment.getActiveProfiles());
        List<String> effective = profiles.isEmpty() ? List.of(environment.getDefaultProfiles()) : profiles;
        ModuleEventRealE2eFixtures.require(effective.contains("local")
                && effective.stream().noneMatch(Set.of("prod", "staging", "ci", "test")::contains),
                "local プロファイル以外では起動しない: " + effective);
        URI jdbc = URI.create(environment.getRequiredProperty("spring.datasource.url").substring("jdbc:".length()));
        ModuleEventRealE2eFixtures.require("mysql".equals(jdbc.getScheme()) && loopback(jdbc.getHost()),
                "ループバックの MySQL 以外へは検体を投入しない");
        ModuleEventRealE2eFixtures.require(environment.getProperty("mannschaft.email.simulate", Boolean.class, false),
                "メール送信は simulate（LoggingEmailTransport）に限る");
    }

    private static boolean loopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static void stopWorkers(ConfigurableApplicationContext context) throws InterruptedException {
        List<ThreadPoolTaskScheduler> schedulers = List.copyOf(context.getBeansOfType(ThreadPoolTaskScheduler.class).values());
        List<ThreadPoolTaskExecutor> executors = List.copyOf(context.getBeansOfType(ThreadPoolTaskExecutor.class).values());
        schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
        for (ThreadPoolTaskScheduler scheduler : schedulers) {
            ModuleEventRealE2eFixtures.require(scheduler.getScheduledThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS),
                    "scheduler が停止しなかった");
        }
        for (ThreadPoolTaskExecutor executor : executors) {
            ModuleEventRealE2eFixtures.require(executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS),
                    "executor が停止しなかった");
        }
    }

    private static void write(ObjectMapper mapper, Path target, Object value) throws IOException {
        Path next = target.resolveSibling(target.getFileName() + ".next");
        mapper.writerWithDefaultPrettyPrinter().writeValue(next.toFile(), value);
        Files.move(next, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
