package fr.bubblebd

/** Reading recency comes first; unopened albums follow their import date. */
object MetadataOrder {
    fun ordered(books:List<Book>)=books.sortedWith(
        compareByDescending<Book> {it.lastRead.coerceAtLeast(0)}
            .thenByDescending {it.added}
            .thenBy {it.id}
    )
}
