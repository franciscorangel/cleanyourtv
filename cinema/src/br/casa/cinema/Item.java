package br.casa.cinema;

/** One card: a title from an app's Watch Next / channel, or an installed app. */
final class Item {
    static final int CONTINUE = 0, FOR_YOU = 1, APP = 2;

    final int kind;
    String pkg;
    String appLabel;
    String title;
    String subtitle;       // card line under the title
    String meta;           // hero line under the title
    String description;
    String imageUri;       // card art
    String backdropUri;    // the same art at the largest size its server offers
    String intentUri;      // deep link, or null for an app
    int progress = -1;     // 0..100, -1 when unknown
    long engaged;          // last engagement, epoch ms (0 when the app gives none)
    long position;         // playback position, ms (0 when unknown)
    String left;           // "faltam 21 min", or null
    long channel;          // preview channel id, for preview programs

    Item(int kind) { this.kind = kind; }

    String key() { return kind + "|" + pkg + "|" + title + "|" + subtitle; }

    /** Removed from Continue watching until this changes, i.e. until it is watched again in its app. */
    String hiddenKey() { return key() + "\t" + position + "/" + progress; }
}
