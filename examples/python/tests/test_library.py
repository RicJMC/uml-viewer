"""The example's borrowing and catalog contracts."""

from library.book import Book
from library.catalog import MemoryCatalog


def test_a_book_can_be_borrowed_again_after_return():
    book = Book("Clean Code")
    assert book.borrow() is True
    assert book.borrow() is False
    book.return_book()
    assert book.borrow() is True


def test_catalog_finds_the_requested_book():
    first = Book("Clean Code")
    second = Book("Clean Architecture")
    catalog = MemoryCatalog([first, second])
    assert catalog.find("Clean Architecture") is second
