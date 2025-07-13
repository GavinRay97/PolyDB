db = db.getSiblingDB("review_db");
db.createCollection("reviews");

db.reviews.insertMany([
  {
    product_id: 1,
    user_id: 1,
    rating: 5,
    comment: "This laptop is fantastic! Super fast and reliable.",
    created_at: new Date("2023-01-15T10:00:00Z"),
  },
  {
    product_id: 2,
    user_id: 2,
    rating: 4,
    comment: "Great phone, but the battery could be better.",
    created_at: new Date("2023-02-20T14:30:00Z"),
  },
  {
    product_id: 3,
    user_id: 1,
    rating: 5,
    comment: "Amazing sound quality and very comfortable.",
    created_at: new Date("2023-01-18T11:00:00Z"),
  },
  {
    product_id: 3,
    user_id: 3,
    rating: 3,
    comment: "Good headphones, but they feel a bit flimsy.",
    created_at: new Date("2023-03-01T09:00:00Z"),
  },
]);
