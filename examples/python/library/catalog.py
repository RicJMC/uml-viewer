"""Catalogs find books by title."""

from abc import ABC, abstractmethod

from .book import Book


class Catalog(ABC):
    @abstractmethod
    def find(self, title) -> Book:
        raise NotImplementedError


class MemoryCatalog(Catalog):
    def __init__(self, books):
        self.books = {book.title: book for book in books}

    def find(self, title) -> Book:
        return self.books[title]
