// Header scroll behavior
let lastScrollTop = 0;
const header = document.querySelector(".header");

if (header) {
    window.addEventListener("scroll", function() {
        let currentScroll = window.pageYOffset || document.documentElement.scrollTop;

        if (currentScroll > lastScrollTop && currentScroll > header.offsetHeight) {
            // Cuộn xuống -> Thêm class "hide"
            header.classList.add("hide");
        } else {
            // Cuộn lên -> Xóa class "hide"
            header.classList.remove("hide");
        }

        lastScrollTop = currentScroll <= 0 ? 0 : currentScroll;
    });
}