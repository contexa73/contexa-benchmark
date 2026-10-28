package io.contexa.bench.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The guard classifies tools only from the frozen files of the benchmark and refuses a file edited after the freeze. */
class ToolTableTest {

    private static final Path DECLARATIONS = Path.of("E:/projects/contexa-benchmark/declarations");

    @Test
    void everyToolOfBothBenchmarksIsListedAndClassifiedFromTheFrozenFiles() {
        ToolTable table = new ToolTable(DECLARATIONS);

        assertThat(table.listedTools()).isEqualTo(69 + 79);
        assertThat(table.fileHashes()).containsOnlyKeys("agentdojo-tools.yml", "injecagent-tools.yml");

        assertThat(table.of("get_all_hotels_in_city").toolClass()).isEqualTo(ToolClass.READ_PUBLIC);
        assertThat(table.of("get_balance").toolClass()).isEqualTo(ToolClass.READ);
        assertThat(table.of("get_webpage").toolClass()).isEqualTo(ToolClass.READ);
        assertThat(table.of("send_money").toolClass()).isEqualTo(ToolClass.MONEY);
        assertThat(table.of("reserve_hotel").toolClass()).isEqualTo(ToolClass.MONEY);
        assertThat(table.of("delete_file").toolClass()).isEqualTo(ToolClass.DELETE);
        assertThat(table.of("send_email").toolClass()).isEqualTo(ToolClass.EXTERNAL);
        assertThat(table.of("create_calendar_event").toolClass()).isEqualTo(ToolClass.EXTERNAL);
        assertThat(table.of("update_password").toolClass()).isEqualTo(ToolClass.CREDENTIAL);
        assertThat(table.of("TerminalExecute").toolClass()).isEqualTo(ToolClass.CREDENTIAL);
        assertThat(table.of("NortonIdentitySafeSearchPasswords").toolClass()).isEqualTo(ToolClass.CREDENTIAL);
        assertThat(table.of("The23andMeDownloadData").toolClass()).isEqualTo(ToolClass.EXTERNAL);
        assertThat(table.of("append_to_file").toolClass()).isEqualTo(ToolClass.WRITE);
        assertThat(table.of("update_user_info").toolClass()).isEqualTo(ToolClass.WRITE);
    }

    @Test
    void anUnlistedToolIsAWriteNotAReadOnlyTool() {
        ToolTable table = new ToolTable(DECLARATIONS);

        assertThat(table.of("get_something_new").toolClass()).isEqualTo(ToolClass.WRITE);
        assertThat(table.of("get_something_new").basis()).isEqualTo("unknown");
    }

    @Test
    void aFileEditedAfterTheFreezeStopsTheStart(@TempDir Path directory) throws IOException {
        for (String name : new String[]{"freeze.json", "agentdojo-tools.yml", "injecagent-tools.yml"}) {
            Files.copy(DECLARATIONS.resolve(name), directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(directory.resolve("agentdojo-tools.yml"),
                Files.readString(directory.resolve("agentdojo-tools.yml")).replace("get_balance:", "get_balance_:"));

        assertThatThrownBy(() -> new ToolTable(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differs from its freeze");
    }
}
