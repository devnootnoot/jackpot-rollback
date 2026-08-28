package me.nootnoot.edge.present;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.StyleBuilderApplicable;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class CoreText {

    public static final String FLOW_MAIN_HEX = "#6CE4FD";
    public static final String FLOW_ACCENT_HEX = "#DF80FE";

    public static final String PVPHQ_MAIN_HEX = "#FFBF5C";
    public static final String PVPHQ_ACCENT_HEX = "#FFE5B4";

    private static final boolean FLOW_ENABLED = "true".equals(System.getenv("FLOWPVP"));

    public static final String MAIN_HEX = FLOW_ENABLED ? FLOW_MAIN_HEX : PVPHQ_MAIN_HEX;
    public static final String ACCENT_HEX = FLOW_ENABLED ? FLOW_ACCENT_HEX : PVPHQ_ACCENT_HEX;

    private static final StyleBuilderApplicable MAIN_COLOR_APPLY =
            s -> s.color(TextColor.fromHexString(MAIN_HEX));
    private static final StyleBuilderApplicable ACCENT_COLOR_APPLY =
            s -> s.color(TextColor.fromHexString(ACCENT_HEX));

    private static final TagResolver CUSTOM_TAGS = TagResolver.builder()
            .resolver(Placeholder.styling("main_color", MAIN_COLOR_APPLY))
            .resolver(Placeholder.styling("accent_color", ACCENT_COLOR_APPLY))
            .build();

    private static final MiniMessage MM = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .resolver(StandardTags.gradient())
                    .resolver(StandardTags.reset())
                    .resolver(CUSTOM_TAGS)
                    .build())
            .build();

    private CoreText() {
    }

    public static Component mini(String message) {
        return MM.deserialize(message);
    }

    public static Component legacy(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }
}
