// References to DOM Elements
const prevBtn = document.querySelector("#prev-btn");
const nextBtn = document.querySelector("#next-btn");
const book = document.querySelector("#book");

// Get all paper elements dynamically
const papers = document.querySelectorAll('.paper');
const numOfPapers = papers.length;
const maxLocation = numOfPapers + 1;

console.log(`Detected ${numOfPapers} papers`);

// Event Listeners
if (prevBtn && nextBtn && book && papers.length > 0) {
    prevBtn.addEventListener("click", goPrevPage);
    nextBtn.addEventListener("click", goNextPage);
}

// Business Logic
let currentLocation = 1;

// Khởi tạo z-index ban đầu cho tất cả papers
function initializeZIndex() {
    papers.forEach((paper, index) => {
        paper.style.zIndex = numOfPapers - index;
    });
}

function openBook() {
    book.style.transform = "translateX(50%)";
    prevBtn.style.transform = "translateX(-180px)";
    nextBtn.style.transform = "translateX(180px)";
}

function closeBook(isAtBeginning) {
    if(isAtBeginning) {
        book.style.transform = "translateX(0%)";
    } else {
        book.style.transform = "translateX(100%)";
    }

    prevBtn.style.transform = "translateX(0px)";
    nextBtn.style.transform = "translateX(0px)";
}

function goNextPage() {
    if(currentLocation < maxLocation) {
        const paperToFlip = currentLocation - 1; // Paper cần lật
        console.log(`Going to page ${currentLocation + 1}, flipping paper ${paperToFlip}`);

        // Animation lật paper
        if(currentLocation === 1) {
            openBook();
        } else if(currentLocation === maxLocation - 1) {
            closeBook(false);
        }

        papers[paperToFlip].classList.add("flipped");
        papers[paperToFlip].style.zIndex = 1; // Paper đã lật có z-index thấp nhất

        // Chuyển trang sau khi animation
        currentLocation++;
    }
}

function goPrevPage() {
    if(currentLocation > 1) {
        const paperToUnflip = currentLocation - 2; // Paper cần unflip
        console.log(`Going back to page ${currentLocation - 1}, unflipping paper ${paperToUnflip}`);

        // Animation unflip paper
        if(currentLocation === 2) {
            closeBook(true);
        } else if(currentLocation === maxLocation) {
            openBook();
        }

        papers[paperToUnflip].classList.remove("flipped");
        papers[paperToUnflip].style.zIndex = numOfPapers - paperToUnflip; // Khôi phục z-index ban đầu

        // Chuyển trang sau khi animation
        currentLocation--;
    }
}

// Khởi tạo z-index khi trang được load
document.addEventListener('DOMContentLoaded', function() {
    if (papers.length > 0) {
        initializeZIndex();
    }
});

/* -------------------------------
   Flipbook iframe loader & controls
   Injects a spinner while the iframe loads and a small control bar
   ------------------------------- */
(function setupFlipbookHelpers() {
    // Wait for DOM ready (in case this script is deferred differently)
    function init() {
        const container = document.querySelector('.flipbook-premium-container');
        if (!container) return; // nothing to do

        const iframe = container.querySelector('.flipbook-premium-iframe');
        if (!iframe) return;

        // Reuse existing loader in HTML if available to avoid duplicates
        const loader = container.querySelector('.flipbook-loading') || document.createElement('div');
        if (!loader.classList.contains('flipbook-loading')) {
            loader.className = 'flipbook-loading';
        }
        if (!loader.parentNode) {
            container.appendChild(loader);
        }

        // Reuse existing controls in HTML if available to avoid duplicates
        const controls = container.querySelector('.flipbook-premium-controls') || document.createElement('div');
        if (!controls.classList.contains('flipbook-premium-controls')) {
            controls.className = 'flipbook-premium-controls';
        }
        if (!controls.parentNode) {
            container.appendChild(controls);
        }

        if (!controls.querySelector('.fb-open')) {
            controls.innerHTML = `
                <button type="button" class="fb-open" title="Mở trong tab mới"><i class="bi bi-box-arrow-up-right"></i></button>
                <button type="button" class="fb-full" title="Toàn màn hình"><i class="bi bi-arrows-fullscreen"></i></button>
                <button type="button" class="fb-reload" title="Tải lại"><i class="bi bi-arrow-clockwise"></i></button>
            `;
        }

        const btnOpen = controls.querySelector('.fb-open');
        const btnFull = controls.querySelector('.fb-full');
        const btnReload = controls.querySelector('.fb-reload');

        // Open iframe src in new tab
        btnOpen.addEventListener('click', function() {
            try { window.open(iframe.src, '_blank', 'noopener'); }
            catch (e) { window.location.href = iframe.src; }
        });

        // Reload iframe
        btnReload.addEventListener('click', function() {
            try {
                if (iframe.contentWindow && iframe.contentWindow.location) {
                    iframe.contentWindow.location.reload();
                } else {
                    iframe.src = iframe.src;
                }
            } catch (e) {
                iframe.src = iframe.src;
            }
        });

        // Fullscreen the container
        btnFull.addEventListener('click', async function() {
            try {
                if (container.requestFullscreen) await container.requestFullscreen();
                else if (container.webkitRequestFullscreen) await container.webkitRequestFullscreen();
                else if (container.msRequestFullscreen) await container.msRequestFullscreen();
            } catch (e) {
                console.warn('Fullscreen request failed', e);
            }
        });

        // Remove loader when iframe loads
        function onLoaded() {
            container.classList.remove('loading');
            if (loader.parentNode) loader.parentNode.removeChild(loader);
            // Adjust height to fit content exactly (hide ad/footer)
            setTimeout(() => {
                try {
                    const innerDoc = iframe.contentDocument || iframe.contentWindow.document;
                    const bookElement = innerDoc.querySelector('.flipbook'); // Assuming class from flipbookpdf.net
                    if (bookElement) {
                        const bookHeight = bookElement.offsetHeight;
                        iframe.style.height = `${bookHeight}px`;
                    }
                } catch (e) {
                    console.warn('Could not access iframe content for height adjustment', e);
                }
            }, 1000); // Delay to ensure content is rendered
        }

        iframe.addEventListener('load', onLoaded);

        // Fallback timeout
        const fallback = setTimeout(() => {
            onLoaded();
        }, 12000);

        // Check if already loaded
        try {
            if (iframe.contentDocument && iframe.contentDocument.readyState === 'complete') {
                onLoaded();
                clearTimeout(fallback);
            }
        } catch (e) {}
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();   